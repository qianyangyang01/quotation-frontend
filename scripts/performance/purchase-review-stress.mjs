import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { setTimeout as sleep } from 'node:timers/promises'

const base = process.env.PERF_BASE_URL || 'http://127.0.0.1:18239'
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(base)) throw Error('Isolated loopback only')
const users = Number(process.env.PERF_USERS || 50)
const seconds = Number(process.env.PERF_SECONDS || 120)
const run = Date.now().toString(36).toUpperCase()
const metrics = {}, errors = [], observations = [], checks = []
let active = 0, peak = 0
function sample(name, ms) { (metrics[name] ||= []).push(ms) }
class Session {
  constructor(account) { this.account=account;this.cookies=new Map() }
  async req(path, method='GET', body, name) {
    const headers={Accept:'application/json',Cookie:[...this.cookies].map(([k,v])=>`${k}=${v}`).join('; ')}
    if(this.csrf&&method!=='GET') headers[this.csrf.headerName]=this.csrf.token
    if(body) headers['Content-Type']='application/json'
    const started=performance.now();active++;peak=Math.max(active,peak)
    try {
      const response=await fetch(base+'/api/v1'+path,{method,headers,body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(65000)})
      for(const cookie of response.headers.getSetCookie()){const pair=cookie.split(';')[0],i=pair.indexOf('=');this.cookies.set(pair.slice(0,i),pair.slice(i+1))}
      const value=await response.json()
      if(!response.ok) throw Object.assign(Error(`${response.status} ${method} ${path}: ${value.message}`),{status:response.status})
      if(name) sample(name,performance.now()-started)
      return value.data
    }finally{active--}
  }
  async login(){this.csrf=await this.req('/auth/csrf');await this.req('/auth/login','POST',{account:this.account,password:process.env.PERF_PASSWORD||'PerfAdmin123!'})}
}
const product=sku=>({sku,category:'服装',weightG:125,minOrderQty:1,purchasePriceCny:12.34,freeShipping:'是',taxPoint:0,stockStatus:'有货'})
const sessions=Array.from({length:users},(_,i)=>new Session('SYNC'+String(i+1).padStart(3,'0')))
const admin=new Session('PERFADMIN'),purchase=new Session('PERFPUR')
await admin.login();await purchase.login()
for(let i=0;i<users;i+=10) await Promise.all(sessions.slice(i,i+10).map(s=>s.login()))
const employeeSessions=sessions.filter((_,i)=>i%5<3)
const purchaseSessions=sessions.filter((_,i)=>i%5===3)
const adminSessions=sessions.filter((_,i)=>i%5===4)
const quoteRows=new Map()
for(const s of employeeSessions){const page=await s.req('/quotations/search?scope=mine&page=0&size=10');assert(page.items.length);quoteRows.set(s.account,page.items)}
async function check(name,action){try{await action();checks.push({name,passed:true})}catch(e){checks.push({name,passed:false,error:e.message})}}
await check('100-row paste; all rows immediately searchable by employee and admin',async()=>{
  const rows=Array.from({length:100},(_,i)=>product(`PV-${run}-${i}`))
  assert.equal((await purchase.req('/purchase-products/paste','POST',rows,'paste-100')).length,100)
  await Promise.all([employeeSessions[0],admin].map(async s=>{
    const p=await s.req('/purchase-products?q='+`PV-${run}`+'&size=100', 'GET',undefined,'immediate-search-100')
    assert.equal(p.total,100);assert.equal(new Set(p.items.map(x=>x.sku)).size,100)
    for(const row of p.items) assert.equal(row.purchasePriceCny,12.34)
  }))
  assert.equal((await purchase.req('/purchase-products/paste','POST',rows)).length,0)
})
await check('invalid batch is atomic and 101 rows rejected',async()=>{
  const sku=`IV-${run}`
  await assert.rejects(()=>purchase.req('/purchase-products/paste','POST',[product(sku),{...product(sku+'-BAD'),taxPoint:null}]),e=>e.status===422)
  assert.equal((await admin.req('/purchase-products?q='+sku)).total,0)
  await assert.rejects(()=>purchase.req('/purchase-products/paste','POST',Array.from({length:101},(_,i)=>product(sku+i))),e=>e.status===422)
})
await check('employee cannot paste/review or read another employee review state',async()=>{
  const a=employeeSessions[0],b=employeeSessions[1],row=quoteRows.get(b.account)[0]
  await assert.rejects(()=>a.req('/purchase-products/paste','POST',[product(`DENY-${run}`)]),e=>e.status===403)
  await assert.rejects(()=>a.req(`/quotations/${row.id}/finance-review`,'PATCH',{action:'claim',_version:row._version,_reviewVersion:row._reviewVersion}),e=>e.status===403)
  assert.deepEqual(await a.req('/quotations/review-status?ids='+row.id),[])
})
await check('simultaneous duplicate paste never overwrites or duplicates',async()=>{
  const sku=`DUP-${run}`
  const results=await Promise.allSettled(purchaseSessions.map((s,i)=>s.req('/purchase-products/paste','POST',[{...product(sku),purchasePriceCny:i+1}])))
  const failures=results.filter(r=>r.status==='rejected').map(r=>({status:r.reason.status,error:r.reason.message}))
  observations.push({case:'same-SKU paste race',requests:results.length,rejected:failures})
  assert(failures.every(e=>e.status===409),'duplicate race must not produce HTTP 500')
  const page=await admin.req('/purchase-products?q='+sku)
  assert.equal(page.total,1)
  assert.equal(results.filter(r=>r.status==='fulfilled').reduce((n,r)=>n+r.value.length,0),1)
})
await check('simultaneous review claim has exactly one winner',async()=>{
  const row=quoteRows.get(employeeSessions[0].account)[0]
  const fresh=await admin.req('/quotations/'+row.id)
  const results=await Promise.allSettled(adminSessions.map(s=>s.req(`/quotations/${row.id}/finance-review`,'PATCH',{action:'claim',_version:fresh._version,_reviewVersion:fresh._reviewVersion})))
  assert.equal(results.filter(r=>r.status==='fulfilled').length,1)
  assert(results.filter(r=>r.status==='rejected').every(r=>r.reason.status===409))
  const winner=results.findIndex(r=>r.status==='fulfilled'),claimed=results[winner].value
  await adminSessions[winner].req(`/quotations/${row.id}/finance-review`,'PATCH',{action:'complete',financeReviewStatus:'approved',_version:claimed._version,_reviewVersion:claimed._reviewVersion})
  await assert.rejects(()=>admin.req(`/quotations/${row.id}/finance-review`,'PATCH',{action:'claim',_version:fresh._version,_reviewVersion:fresh._reviewVersion}),e=>e.status===409)
})
await check('employee/admin product, quotation, list and review-state payloads are identical',async()=>{
  const employee=employeeSessions[0],row=quoteRows.get(employee.account)[0]
  for(const path of ['/purchase-products/PERF-SKU-00001','/purchase-products?q=PERF-SKU-00001',`/quotations/${row.id}`,`/quotations/review-status?ids=${row.id}`]) {
    assert.deepEqual(await employee.req(path),await admin.req(path),path)
  }
  const mine=await employee.req('/quotations/search?scope=mine&q='+row.no)
  const company=await admin.req('/quotations/search?scope=company&q='+row.no)
  assert.deepEqual(mine.items,company.items)
})
await check('all sessions can search a fresh SKU in synchronized bursts',async()=>{
  for(let round=0;round<5;round++) {
    const sku=`BURST-${run}-${round}`;await purchase.req('/purchase-products/paste','POST',[product(sku)])
    await Promise.all(sessions.map(async s=>{const page=await s.req('/purchase-products?q='+sku,'GET',undefined,'synchronized-search-burst');assert.equal(page.total,1);assert.equal(page.items[0].purchasePriceCny,12.34)}))
  }
})

const latest=[],pending=new Map();let writes=0,reviewed=0,seen=0
const begin=performance.now(),end=begin+seconds*1000
async function reader(s){
  const rows=quoteRows.get(s.account),ids=rows.map(r=>r.id).join(',');let n=0
  while(performance.now()<end){
    try{
      const states=await s.req('/quotations/review-status?ids='+ids,'GET',undefined,'review-poll-10')
      const now=performance.now()
      for(const state of states){const p=pending.get(state.id);if(p&&state._reviewVersion>=p.version&&state.financeReviewStatus===p.status){sample('review-visible-after-ack',now-p.at);seen++;pending.delete(state.id)}}
      const filtered=await s.req('/quotations/search?scope=mine&reviewStatus=approved&page=0&size=10','GET',undefined,'employee-approved-list')
      assert(filtered.items.every(r=>r.financeReviewStatus==='approved'))
      if(n++%2===0){const item=latest.at(-1);if(item){const page=await s.req('/purchase-products?q='+item.sku,'GET',undefined,'employee-search');assert(page.items.some(r=>r.sku===item.sku&&r.purchasePriceCny===12.34))}}
    }catch(e){errors.push({role:'employee',message:e.message})}
    await sleep(3000)
  }
}
async function writer(s,i){let n=0
  while(performance.now()<end){
    try{
      const batch=Array.from({length:10},(_,j)=>product(`LD-${run}-${i}-${n}-${j}`));n++
      const saved=await s.req('/purchase-products/paste','POST',batch,'paste-10');assert.equal(saved.length,10);writes+=saved.length
      latest.push(saved[0]);if(latest.length>100)latest.shift()
      const started=performance.now()
      const page=await s.req('/purchase-products?q='+batch[0].sku,'GET',undefined,'purchase-immediate-search');assert(page.items.some(r=>r.sku===batch[0].sku));sample('paste-visible-after-ack',performance.now()-started)
    }catch(e){errors.push({role:'purchase',message:e.message})}
    await sleep(500)
  }
}
async function reviewer(s,i){const own=employeeSessions.filter((_,j)=>j%adminSessions.length===i).flatMap(e=>quoteRows.get(e.account));let n=0
  while(performance.now()<end){
    const row=own[n++%own.length]
    if(!row||pending.has(row.id)){await sleep(100);continue}
    try{
      const current=await s.req('/quotations/'+row.id)
      const claimed=await s.req(`/quotations/${row.id}/finance-review`,'PATCH',{action:'claim',_version:current._version,_reviewVersion:current._reviewVersion},'review-claim')
      const status=n%2?'approved':'rejected'
      const saved=await s.req(`/quotations/${row.id}/finance-review`,'PATCH',{action:'complete',financeReviewStatus:status,note:'Isolated synchronization load',_version:claimed._version,_reviewVersion:claimed._reviewVersion},'review-complete')
      pending.set(row.id,{at:performance.now(),status,version:saved._reviewVersion});reviewed++
      const item=latest.at(-1)
      if(item){const page=await s.req('/purchase-products?q='+item.sku,'GET',undefined,'admin-search');assert(page.items.some(r=>r.sku===item.sku))}
    }catch(e){errors.push({role:'admin',message:e.message})}
    await sleep(350)
  }
}
await Promise.all([...employeeSessions.map(reader),...purchaseSessions.map(writer),...adminSessions.map(reviewer)])
// Drain every acknowledged review, independent of whether it was changed near the stage boundary.
for(const s of employeeSessions){const ids=quoteRows.get(s.account).map(r=>r.id).join(',');const states=await s.req('/quotations/review-status?ids='+ids);for(const state of states){const p=pending.get(state.id);if(p){assert.equal(state.financeReviewStatus,p.status);assert(state._reviewVersion>=p.version);pending.delete(state.id)}}}
const summary=Object.fromEntries(Object.entries(metrics).map(([k,v])=>{v.sort((a,b)=>a-b);const p=q=>+v[Math.min(v.length-1,Math.ceil(v.length*q)-1)].toFixed(2);return[k,{count:v.length,p50:p(.5),p95:p(.95),p99:p(.99),max:p(1)}]}))
const report={run,base,users,seconds,roles:{employee:employeeSessions.length,purchase:purchaseSessions.length,admin:adminSessions.length},peakRequests:peak,checks,observations,writes,reviewed,observedDuringLoad:seen,pendingAfterDrain:pending.size,errors:errors.slice(0,30),errorCount:errors.length,metrics:summary,passed:!errors.length&&checks.every(c=>c.passed)&&!pending.size}
await writeFile(process.env.PERF_OUTPUT||'artifacts/sync-stress/load.json',JSON.stringify(report,null,2))
console.log(JSON.stringify(report,null,2));if(!report.passed)process.exitCode=1
