import assert from 'node:assert/strict'
import {writeFile} from 'node:fs/promises'
import {Session,cleanQuotation,refreshVersions,priceSnapshot,setObserver} from './client.mjs'

const checks=[],timings=[]
const admin=new Session('PERFADMIN'),employee=new Session('LOAD001'),other=new Session('LOAD002'),purchase=new Session('LOAD031'),finance=new Session('LOAD041'),finance2=new Session('LOAD042'),logistics=new Session('LOAD045')
for(const s of [admin,employee,other,purchase,finance,finance2,logistics])await s.login()
async function check(name,fn){const start=performance.now();try{await fn();checks.push({name,passed:true,ms:Math.round(performance.now()-start)});console.log('PASS',name)}catch(e){checks.push({name,passed:false,error:e.message});console.log('FAIL',name,e.message)}}
async function pages(path,size){const first=await admin.request(path+'?scope=company&size='+size);const rows=[...first.items];for(let i=1;i<first.totalPages;i++)rows.push(...(await admin.request(path+'?scope=company&size='+size+'&page='+i)).items);assert.equal(rows.length,first.total);return rows}
let originalQuotes=[]
await check('Full production-size catalog and quotation snapshot match existing endpoints',async()=>{
  for(const [oldPath,newPath,size,fields] of [
    ['/purchase-products','/purchase-products/analytics-catalog',500,['sku','category','purchasePriceCny']],
    ['/quotations','/quotations/analytics-records',100,['id','no','primarySku','customerName','productSummary','salespersonName','salespersonAccount','country','status','systemQuoteUsd','systemQuoteCny','totalCostCny','exchangeRate','createdAt','updatedAt']],
  ]){
    let requests=0,bytes=0;setObserver(s=>{requests++;bytes+=s.bytes})
    const start=performance.now(),full=await pages(oldPath,size)
    const legacy={ms:performance.now()-start,requests,bytes,count:full.length}
    requests=0;bytes=0;const compactStart=performance.now(),compact=await admin.request(newPath)
    const optimized={ms:performance.now()-compactStart,requests,bytes,count:compact.total}
    const key=fields[0],lookup=new Map(full.map(p=>[p[key],p]))
    assert.equal(compact.total,full.length)
    for(const row of compact.items){const expected=lookup.get(row[key]);assert(expected);for(const f of fields)assert.deepEqual(row[f]??null,expected[f]??null,'field '+f)}
    if(oldPath==='/quotations'){
      originalQuotes=full
      for(const row of compact.items){const source=lookup.get(row.id);const options=source.quoteOptions||source.specifiedQuotes||[];assert.deepEqual(row.quoteOptions.map(v=>v.country),options.map(v=>v.country))}
    }
    timings.push({oldPath,newPath,legacy,optimized})
  }
  setObserver(()=>{})
})
await check('Five-role permission isolation for analytics, purchases, finance and review',async()=>{
  for(const s of [employee,purchase,logistics])for(const path of ['/purchase-products/analytics-catalog','/quotations/analytics-records'])assert.equal((await s.request(path,{allow:[403]})).expectedStatus,403)
  assert.equal((await employee.request('/users',{allow:[403]})).expectedStatus,403)
  assert.equal((await logistics.request('/purchase-products?size=1',{allow:[403]})).expectedStatus,403)
  assert.equal((await purchase.request('/quotations?scope=company',{allow:[403]})).expectedStatus,403)
  assert((await finance.request('/quotations/analytics-records')).total>0)
})

const fixtures=[],fixtureErrors=[]
await check('Current single and bundle quotation fixtures save with verified revisions and unchanged monetary snapshots',async()=>{
  if(!originalQuotes.length)originalQuotes=await pages('/quotations',100)
  for(const mode of ['single','bundle']){
    for(const source of originalQuotes.filter(q=>q.quoteMode===mode).slice(0,25)){
      try{
        const body=await refreshVersions(employee,cleanQuotation(source));body.customerName='QA-PREFLIGHT-'+mode
        const row=await employee.request('/quotations',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body})
        const back=await employee.request('/quotations/'+row.id)
        assert.equal(priceSnapshot(back),priceSnapshot(row))
        fixtures.push(body);break
      }catch(e){fixtureErrors.push({mode,error:e.message})}
    }
    assert(fixtures.some(f=>f.quoteMode===mode),'No valid '+mode+' fixture: '+fixtureErrors.slice(-2).map(e=>e.error))
  }
  await writeFile('artifacts/performance/quote-fixtures.json',JSON.stringify(fixtures,null,2))
})
async function createQuote(){assert(fixtures.length);const body=await refreshVersions(employee,cleanQuotation(fixtures[0]));body.customerName='QA-LINK-'+crypto.randomUUID();return employee.request('/quotations',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body})}
await check('Claim response version completes immediately; competing reviewer and stale completion are rejected; employee/admin agree',async()=>{
  const row=await createQuote(),before=priceSnapshot(row)
  const attempts=await Promise.all([finance,finance2].map(s=>s.request('/quotations/'+row.id+'/finance-review',{method:'PATCH',body:{action:'claim',_version:row._version,_reviewVersion:row._reviewVersion},allow:[409]})))
  assert.equal(attempts.filter(x=>x.expectedStatus===409).length,1)
  const index=attempts.findIndex(x=>!x.expectedStatus),winner=[finance,finance2][index],claimed=attempts[index]
  const path='/quotations/'+row.id+'/finance-review'
  assert.equal((await winner.request(path,{method:'PATCH',body:{action:'complete',_version:claimed._version,_reviewVersion:claimed._reviewVersion-1,financeReviewStatus:'approved'},allow:[409]})).expectedStatus,409)
  const completed=await winner.request(path,{method:'PATCH',body:{action:'complete',_version:claimed._version,_reviewVersion:claimed._reviewVersion,financeReviewStatus:'approved'}})
  const staff=await employee.request('/quotations/'+row.id),manager=await admin.request('/quotations/'+row.id)
  assert.deepEqual(staff,manager);assert.deepEqual(staff,completed);assert.equal(priceSnapshot(staff),before)
  assert.equal((await other.request('/quotations/'+row.id,{allow:[403]})).expectedStatus,403)
  const poll=await employee.request('/quotations/review-status?ids='+row.id);assert.equal(poll[0].financeReviewStatus,'approved')
})
await check('Finance update invalidates an open quote; historical amounts remain unchanged',async()=>{
  const row=await createQuote(),body=await refreshVersions(employee,cleanQuotation(fixtures[0]))
  const setting=await finance.request('/finance-settings/exchange-rate')
  const updated=await finance.request('/finance-settings/exchange-rate',{method:'PUT',headers:{'If-Match':String(setting._version)},body:{...setting.value,usdCny:Number(setting.value.usdCny)+0.01}})
  try{
    const blocked=await employee.request('/quotations',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body,allow:[409]})
    assert.equal(blocked.expectedStatus,409);assert.match(blocked.message,/财务/)
    assert.equal(priceSnapshot(await employee.request('/quotations/'+row.id)),priceSnapshot(row))
    assert.equal((await finance2.request('/finance-settings/exchange-rate',{method:'PUT',headers:{'If-Match':String(setting._version)},body:setting.value,allow:[409]})).expectedStatus,409)
  }finally{await finance.request('/finance-settings/exchange-rate',{method:'PUT',headers:{'If-Match':String(updated._version)},body:setting.value})}
})
await check('Purchase maintenance is immediately identical for employee/admin, rejects stale edits and records history',async()=>{
  const sku='QA-FUNCTIONAL-'+Date.now(),body={sku,category:'服装',weightG:125,minOrderQty:1,purchasePriceCny:12.34,freeShipping:'是',taxPoint:0,stockStatus:'有货'}
  const created=await purchase.request('/purchase-products/paste',{method:'POST',body:[body]});assert.equal(created.length,1)
  const before=await purchase.request('/purchase-products/'+sku)
  const updated=await purchase.request('/purchase-products/'+sku+'/maintenance',{method:'POST',body:{...before,purchasePriceCny:15.67,weightG:250,singleFreightCny:2}})
  assert.deepEqual(await employee.request('/purchase-products/'+sku),await admin.request('/purchase-products/'+sku))
  assert.equal((await employee.request('/purchase-products?q='+sku)).items[0].purchasePriceCny,15.67)
  assert.equal((await purchase.request('/purchase-products/'+sku+'/maintenance',{method:'POST',body:{...before,purchasePriceCny:99},allow:[409]})).expectedStatus,409)
  assert.equal((await purchase.request('/purchase-products/'+sku))._version,updated._version)
  const history=await purchase.request('/purchase-products/'+sku+'/history');assert(history.total>=1)
  assert.equal((await employee.request('/purchase-products/'+sku+'/history',{allow:[403]})).expectedStatus,403)
})
await check('Draft isolation and optimistic version conflict; templates keep employee ownership',async()=>{
  const state=await employee.request('/quotation-drafts/mine/state')
  const body={schemaVersion:2,customerName:'QA-DRAFT',skuSearch:fixtures[0].primarySku.split(/[、,+\s]+/)[0],quoteMode:'single'}
  const saved=await employee.request('/quotation-drafts/mine/state',{method:'PUT',headers:{'If-Match':String(state.version)},body})
  assert.deepEqual((await employee.request('/quotation-drafts/mine/state')).payload,saved.payload)
  assert.notDeepEqual((await other.request('/quotation-drafts/mine/state')).payload,saved.payload)
  assert.equal((await employee.request('/quotation-drafts/mine/state',{method:'PUT',headers:{'If-Match':String(state.version)},body:{...body,customerName:'stale'},allow:[409]})).expectedStatus,409)
  const template=await employee.request('/quotation-templates',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:{name:'QA-LINK-TEMPLATE',countries:['美国','英国'],skuSearch:body.skuSearch}})
  assert((await employee.request('/quotation-templates')).some(t=>t.id===template.id));assert(!(await other.request('/quotation-templates')).some(t=>t.id===template.id))
})
await check('Archive, recycle bin, restoration and dashboard scope remain linked',async()=>{
  let row=await createQuote()
  for(const action of ['archive','restore','trash','restore']){
    const changed=await employee.request('/quotations/lifecycle',{method:'POST',body:{action,reason:'isolated cross-module acceptance',items:[{id:row.id,version:row._version}]}})
    assert.equal(changed.changed,1)
    row=await employee.request('/quotations/'+row.id)
    const snapshot=await admin.request('/quotations/analytics-records')
    assert.equal(snapshot.items.some(q=>q.id===row.id),action!=='trash')
    assert.deepEqual(row,await admin.request('/quotations/'+row.id))
  }
})
await writeFile('artifacts/performance/functional.json',JSON.stringify({checks,timings,fixtureErrors,passed:checks.every(c=>c.passed)},null,2))
if(checks.some(c=>!c.passed))process.exitCode=1
