import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {Session,refreshVersions,cleanQuotation,priceSnapshot} from './client.mjs'
import {importLogistics} from './logistics-import.mjs'
const checks=[],s=new Session('LOAD001'),peer=new Session('LOAD002'),purchase=new Session('PERFPUR'),finance=new Session('PERFFIN'),logistics=new Session('PERFLOG')
for(const x of [s,peer,purchase,finance,logistics])await x.login()
async function check(name,action){try{await action();checks.push({name,passed:true});console.log('PASS',name)}catch(e){checks.push({name,passed:false,error:e.message});console.log('FAIL',name,e.message)}}
const fixture=JSON.parse(await readFile('artifacts/performance/quote-fixtures.json','utf8'))[0]
await check('Expired session and missing CSRF cannot read or write business data',async()=>{
  const expired=new Session('LOAD003');await expired.login();await expired.request('/auth/logout',{method:'POST'})
  assert.equal((await expired.request('/purchase-products?size=1',{allow:[401]})).expectedStatus,401)
  const original=purchase.csrf;purchase.csrf=null
  try{assert.equal((await purchase.request('/purchase-products/QA-FORBIDDEN',{method:'PUT',body:{weightG:1},allow:[403]})).expectedStatus,403)}finally{purchase.csrf=original}
})
await check('Twenty concurrent duplicate submissions create exactly one record and replay identical prices',async()=>{
  const body=await refreshVersions(s,cleanQuotation(fixture));body.customerName='QA-IDEMPOTENT-'+crypto.randomUUID();const key=crypto.randomUUID()
  const results=await Promise.all(Array.from({length:20},()=>s.request('/quotations',{method:'POST',headers:{'Idempotency-Key':key},body,allow:[409]})))
  const successes=results.filter(r=>!r.expectedStatus);assert(successes.length>0);assert.equal(new Set(successes.map(r=>r.id)).size,1)
  const replay=await s.request('/quotations',{method:'POST',headers:{'Idempotency-Key':key},body});assert.equal(replay.id,successes[0].id)
  assert.equal(priceSnapshot(await s.request('/quotations/'+replay.id)),priceSnapshot(replay))
  const list=await s.request('/quotations/search?scope=mine&q='+encodeURIComponent(body.customerName)+'&size=100');assert.equal(list.items.filter(r=>r.customerName===body.customerName).length,1)
  assert.equal((await peer.request('/quotations/'+replay.id,{allow:[403]})).expectedStatus,403)
  assert.equal((await s.request('/quotations',{method:'POST',headers:{'Idempotency-Key':key},body:{...body,customerName:'different'},allow:[409]})).expectedStatus,409)
})
await check('Concurrent finance changes have one winner and preserve the winning value',async()=>{
  const before=await finance.request('/finance-settings/exchange-rate')
  const results=await Promise.all(Array.from({length:10},()=>finance.request('/finance-settings/exchange-rate',{method:'PUT',headers:{'If-Match':String(before._version)},body:before.value,allow:[409]})))
  assert.equal(results.filter(r=>!r.expectedStatus).length,1);assert.deepEqual((await finance.request('/finance-settings/exchange-rate')).value,before.value)
})
await check('Freight tampering and obsolete logistics revision cannot create quotations',async()=>{
  const body=await refreshVersions(s,cleanQuotation(fixture))
  const stale=await s.request('/quotations',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:{...body,logisticsRevision:'invalid-revision'},allow:[409]});assert.equal(stale.expectedStatus,409)
  body.quoteOptions[0].freightCny+=100
  const wrong=await s.request('/quotations',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body,allow:[409,422]});assert(wrong.expectedStatus)
})
await check('Malformed import leaves the published channel unchanged and a valid retry can publish',async()=>{
  const published=await importLogistics(logistics),before=await logistics.request('/logistics/rebuild/versions/'+published.versionId)
  const form=new FormData();form.append('file',new Blob(['corrupt workbook']),'broken.xlsx')
  const failed=await logistics.request('/logistics/channels/'+published.channelId+'/imports',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:form,allow:[422]})
  assert(failed.expectedStatus===422||!failed.id,'Corrupt import unexpectedly produced a version')
  assert.deepEqual(await logistics.request('/logistics/rebuild/versions/'+published.versionId),before)
  assert((await importLogistics(logistics)).passed)
})
await writeFile('artifacts/performance/faults.json',JSON.stringify({checks,passed:checks.every(r=>r.passed)},null,2));if(checks.some(r=>!r.passed))process.exitCode=1
