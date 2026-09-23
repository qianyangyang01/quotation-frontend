import assert from 'node:assert/strict'
import {quotationDetailsCsv,filterQuotationRecords,buildDashboardSummary} from '../../../src/data/quotationAnalytics.ts'
import {importLogistics} from './logistics-import.mjs'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { Session, setObserver, cleanQuotation, refreshVersions, priceSnapshot, sleep, base, peak, resetPeak } from './client.mjs'

const output=process.env.PERF_OUTPUT_DIR||'artifacts/performance/mixed'
await mkdir(output,{recursive:true})
const fixtures=JSON.parse(await readFile(process.env.PERF_FIXTURE||'artifacts/performance/quote-fixtures.json','utf8'))
assert(fixtures.length>=1,'Run functional fixture preparation first')
const stages=process.env.PERF_STAGES?JSON.parse(process.env.PERF_STAGES):[
  {name:'warmup',users:50,seconds:300}, {name:'sustained',users:50,seconds:1800},
  {name:'peak',users:100,seconds:300}, {name:'recovery',users:50,seconds:300}, {name:'soak',users:50,seconds:3600},
]
assert(stages.every(s=>[50,100].includes(s.users)&&s.seconds>0))
let metrics=null
setObserver(sample=>{
  if(!metrics)return
  metrics.requests++;metrics.bytes+=sample.bytes
  if(sample.expected)metrics.expectedConflicts++
  if(sample.error){metrics.httpFailures++;if(metrics.errors.length<40)metrics.errors.push(sample)}
  const key=sample.method+' '+sample.label
  ;(metrics.samples[key]??=[]).push(sample.ms)
})
const sessions=Array.from({length:Math.max(...stages.map(s=>s.users))},(_,i)=>new Session('LOAD'+String(i+1).padStart(3,'0')))
for(let i=0;i<sessions.length;i+=5)await Promise.all(sessions.slice(i,i+5).map(s=>s.login()))
for(const [i,s] of sessions.entries()){s.sequence=i%20;s.ownQuote=null;s.lastDraft=null;s.product=null}
// Provision independent import fixtures before timing; shared directory edits are
// deliberately serialized while imports and publication remain concurrent below.
for(const s of sessions.filter(s=>s.role==='logistics')) await importLogistics(s)
const reviewQueue=[]
const financeKeys=['exchange-rate','customer-grades','tax-settings','surcharge-settings']
const runId=Date.now().toString(36).toUpperCase()
const percent=(values,p)=>values.length?Math.round([...values].sort((a,b)=>a-b)[Math.ceil(values.length*p)-1]*100)/100:0

async function saveQuote(s,n){
  const body=cleanQuotation(fixtures[n%fixtures.length]);body.customerName=`QA-${runId}-${s.account}-${n}`
  await refreshVersions(s,body)
  const key=crypto.randomUUID()
  let row=await s.request('/quotations',{method:'POST',headers:{'Idempotency-Key':key},body,label:'quotation-save',allow:[409]})
  if(row.expectedStatus){
    for(let attempt=0;row.expectedStatus&&attempt<4;attempt++){
      assert.match(row.message,/财务设置已更新|物流版本已更新|采购.*更新|采购.*变更|商品.*更新/)
      await refreshVersions(s,body)
      row=await s.request('/quotations',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body,label:'quotation-save',allow:[409]})
    }
    assert(!row.expectedStatus,'Quotation could not converge after four version refreshes')
  } else {
    const duplicate=await s.request('/quotations',{method:'POST',headers:{'Idempotency-Key':key},body,label:'quotation-idempotent'})
    assert.equal(duplicate.id,row.id)
  }
  const readback=await s.request('/quotations/'+row.id,{label:'quotation-detail'})
  assert.equal(priceSnapshot(readback),priceSnapshot(row))
  assert.equal(readback.salespersonAccount,s.account)
  s.ownQuote=row
  if(reviewQueue.length<1000)reviewQueue.push({id:row.id,owner:s.account,prices:priceSnapshot(row)})
}
async function review(s,n){
  const queued=reviewQueue.shift()
  if(!queued)return s.request('/quotations/search?scope=company&size=20&reviewStatus=pending',{label:'review-queue'})
  const row=await s.request('/quotations/'+queued.id,{label:'review-detail'})
  const claimed=await s.request('/quotations/'+row.id+'/finance-review',{method:'PATCH',body:{action:'claim',_version:row._version,_reviewVersion:row._reviewVersion},label:'review-claim'})
  const complete=await s.request('/quotations/'+row.id+'/finance-review',{method:'PATCH',body:{action:'complete',_version:claimed._version,_reviewVersion:claimed._reviewVersion,financeReviewStatus:n%2?'approved':'rejected'},label:'review-complete'})
  assert.equal(complete.financeReviewStatus,n%2?'approved':'rejected')
  assert.equal(priceSnapshot(complete),queued.prices)
  const owner=sessions.find(v=>v.account===queued.owner)
  const employee=await owner.request('/quotations/'+row.id,{label:'review-employee-readback'})
  assert.deepEqual(employee,complete)
}
async function operation(s){
  const n=s.sequence++,step=n%20
  const source=fixtures[n%fixtures.length],sku=source.primarySku.split(/[、,+\s]+/)[0]
  if(s.role==='employee'){
    if(step===0)return saveQuote(s,n)
    if(step===1)return s.request('/finance-settings',{label:'finance-read'})
    if(step===2)return s.request('/logistics/published/manifest',{label:'logistics-manifest'})
    if(step===3){
      const country=source.quoteOptions.find(o=>o.available!==false)?.country||'美国'
      const m=await s.request('/logistics/published/manifest',{label:'logistics-manifest'})
      return s.request('/logistics/published/rules?revision='+encodeURIComponent(m.revision)+'&attribute='+encodeURIComponent(source.logisticsAttribute)+'&country='+encodeURIComponent(country),{label:'logistics-rules'})
    }
    if(step===4||step===12)return s.request('/purchase-products?q='+encodeURIComponent(sku.slice(0,4))+'&size=20',{label:'purchase-search'})
    if(step===5){
      const state=await s.request('/quotation-drafts/mine/state',{label:'draft-read'})
      const value={schemaVersion:2,customerName:'QA draft '+n,skuSearch:sku,quoteMode:'single'}
      s.lastDraft=await s.request('/quotation-drafts/mine/state',{method:'PUT',headers:{'If-Match':String(state.version)},body:value,label:'draft-save'})
      return
    }
    if(step===6)return s.request('/quotation-templates',{label:'template-list'})
    if(step===7&&s.ownQuote)return s.request('/quotations/review-status?ids='+s.ownQuote.id,{label:'review-poll'})
    if(step%3===0)return s.request('/quotations/search?scope=mine&size=20',{label:'quotation-search'})
    return s.request('/purchase-products/'+encodeURIComponent(sku),{label:'product-detail'})
  }
  if(s.role==='purchase'){
    const ownSku='QA-MAINT-'+s.account
    if(step===0){
      if(!s.product){
        const existing=await s.request('/purchase-products/'+ownSku,{allow:[404],label:'purchase-fixture-read'})
        s.product=existing.expectedStatus?await s.request('/purchase-products/'+ownSku,{method:'PUT',body:{sku:ownSku,category:'服装',weightG:125,minOrderQty:1,purchasePriceCny:12,singleFreightCny:1,taxPoint:0,stockStatus:'有货'},label:'purchase-create'}):existing
      }
      const update={...s.product,purchasePriceCny:12+(n%3)}
      const saved=await s.request('/purchase-products/'+ownSku+'/maintenance',{method:'POST',body:update,label:'purchase-maintain'})
      const back=await s.request('/purchase-products/'+ownSku,{label:'purchase-readback'})
      assert.equal(back.purchasePriceCny,update.purchasePriceCny);assert.equal(back._version,saved._version);s.product=back
      return s.request('/purchase-products/'+ownSku+'/history',{label:'purchase-history'})
    }
    if(step===1)return s.request('/purchase-products/paste',{method:'POST',body:[{sku:'QA-PASTE-'+runId+'-'+s.account+'-'+n,category:'服装',weightG:125,minOrderQty:1,purchasePriceCny:12.34,freeShipping:'是',taxPoint:0,stockStatus:'有货'}],label:'purchase-paste'})
    if(step===2)return s.request('/purchase-products/stats',{label:'purchase-stats'})
    return s.request('/purchase-products?q='+encodeURIComponent(step%2?sku.slice(0,3):'服装')+'&size=20',{label:'purchase-search'})
  }
  if(s.role==='finance'){
    if(step%4===0)return review(s,n)
    if(step===1){
      const key=financeKeys[(Number(s.account.slice(4))-41)%4]
      const before=await s.request('/finance-settings/'+key,{label:'finance-setting-read'})
      const after=await s.request('/finance-settings/'+key,{method:'PUT',headers:{'If-Match':String(before._version)},body:before.value,label:'finance-save',allow:[409]})
      if(after.expectedStatus)assert.match(after.message,/更新|版本|修改/)
      else assert.deepEqual(after.value,before.value)
      return
    }
    return s.request('/quotations/search?scope=company&size=20&reviewStatus='+ (step%2?'approved':'pending'),{label:'review-search'})
  }
  if(s.role==='super_admin'){
    if(step===0){const c=await s.request('/purchase-products/analytics-catalog',{label:'analytics-catalog'});assert.equal(c.items.length,c.total);assert.equal(new Set(c.items.map(p=>p.sku)).size,c.total);s.catalog=c.items;return}
    if(step===1){const r=await s.request('/quotations/analytics-records',{label:'analytics-records'});assert.equal(r.total,r.items.length);assert.equal(new Set(r.items.map(p=>p.id)).size,r.total);s.analytics=r.items;return}
    if(step===2)return s.request('/users',{label:'users'})
    if(step===3&&s.catalog&&s.analytics){
      const rows=s.analytics.map(r=>({...r,totalCostCny:Number(r.totalCostCny||0),systemQuoteUsd:Number(r.systemQuoteUsd||0),systemQuoteCny:Number(r.systemQuoteCny||0)}))
      const filtered=filterQuotationRecords(rows,{keyword:'QA-',startDate:'',endDate:'',country:'',salesperson:'',category:''},s.catalog)
      assert.equal(buildDashboardSummary(filtered).quotationCount,filtered.length)
      const csv=quotationDetailsCsv(filtered,s.catalog);assert(csv.startsWith('\uFEFF报价编号,'));assert.equal(csv.split('\r\n').length,filtered.length+1)
      await writeFile(output+'/'+s.account+'-export.csv',csv);metrics.exports++;return
    }
    return s.request('/quotations/search?scope=company&size=20',{label:'quotation-company-search'})
  }
  if(n%200===9)return importLogistics(s)
  if(step%3===0)return s.request('/logistics/channels?page=0&size=50',{label:'logistics-channels'})
  if(step%3===1)return s.request('/logistics/providers?page=0&size=50',{label:'logistics-providers'})
  return s.request('/logistics/versions?page=0&size=20',{label:'logistics-versions'})
}

for(const stage of stages){
  metrics={...stage,startedAt:new Date().toISOString(),requests:0,bytes:0,exports:0,httpFailures:0,businessFailures:0,expectedConflicts:0,errors:[],samples:{}}
  resetPeak()
  const started=performance.now(),end=started+stage.seconds*1000
  const progress=setInterval(()=>console.log(JSON.stringify({stage:stage.name,elapsed:Math.round((performance.now()-started)/1000),requests:metrics.requests,httpFailures:metrics.httpFailures,businessFailures:metrics.businessFailures,peak})),30_000)
  await Promise.all(sessions.slice(0,stage.users).map(async(s,index)=>{
    await sleep(index*100)
    while(performance.now()<end){
      try{await operation(s)}catch(error){metrics.businessFailures++;if(metrics.errors.length<40)metrics.errors.push({account:s.account,error:error.message,stack:error.stack})}
      await sleep(Number(process.env.PERF_THINK_MS||1000))
    }
  }))
  clearInterval(progress)
  const operations=Object.fromEntries(Object.entries(metrics.samples).map(([name,values])=>[name,{count:values.length,p50:percent(values,.5),p95:percent(values,.95),p99:percent(values,.99),max:Math.max(...values)}]))
  const thresholds=Object.entries(operations).filter(([name,o])=>!name.includes('logistics-import-')&&o.p95>(name.startsWith('GET ')?1500:2500)).map(([name])=>name)
  const summary={...metrics};delete summary.samples
  const report={...summary,finishedAt:new Date().toISOString(),peakInFlight:peak,operations,thresholdFailures:thresholds,passed:metrics.httpFailures===0&&metrics.businessFailures===0&&(['peak','warmup'].includes(stage.name)||thresholds.length===0),base}
  await writeFile(output+'/'+stage.name+'.json',JSON.stringify(report,null,2))
  console.log(JSON.stringify({stage:stage.name,passed:report.passed,requests:report.requests,errors:report.errors.slice(0,3),thresholds}))
  metrics=null
  if(!report.passed){process.exitCode=1;break}
}
