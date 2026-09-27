import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { buildQuotationPayload } from './quotation-payload.mjs'

const baseUrl = process.env.PERF_BASE_URL || 'http://127.0.0.1:18127'
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(baseUrl)) throw new Error('Only isolated loopback is allowed')
class Session {
  constructor(account) { this.account = account; this.cookies = new Map() }
  async raw(path, { method = 'GET', body, headers = {} } = {}) {
    const h = { Accept: 'application/json', ...headers, Cookie: [...this.cookies].map(([k,v]) => `${k}=${v}`).join('; ') }
    if (body !== undefined) h['Content-Type'] = 'application/json'
    if (this.csrf && method !== 'GET') h[this.csrf.headerName] = this.csrf.token
    const response = await fetch(`${baseUrl}/api/v1${path}`, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000) })
    for (const cookie of response.headers.getSetCookie()) { const pair = cookie.split(';')[0]; const i = pair.indexOf('='); this.cookies.set(pair.slice(0,i),pair.slice(i+1)) }
    return { status: response.status, ...(await response.json()) }
  }
  async get(path, options) { const r = await this.raw(path, options); assert.equal(r.status,200,JSON.stringify(r)); return r.data }
  async login() { this.csrf=await this.get('/auth/csrf'); await this.get('/auth/login',{method:'POST',body:{account:this.account,password:process.env.PERF_PASSWORD||'PerfAdmin123!'}}) }
}
const [admin, employee, peer, finance, purchase, logistics] = ['PERFADMIN','PERF01','PERF02','PERFFIN','PERFPUR','PERFLOG'].map(a=>new Session(a))
for (const s of [admin,employee,peer,finance,purchase,logistics]) await s.login()
const cases=[]
async function check(name, action) {
  try { await action(); cases.push({name,passed:true}) }
  catch (error) { cases.push({name,passed:false,error:error.stack}); }
  process.stdout.write(JSON.stringify(cases.at(-1))+'\n')
}
const post = body => ({method:'POST',body,headers:{'Idempotency-Key':crypto.randomUUID()}})
const revision=(await employee.get('/logistics/published/manifest')).revision
function averagePayload(mode='equal') {
  const body=buildQuotationPayload('PERF01',8000,false,revision)
  body.customerName='DEEP-AU-'+crypto.randomUUID()
  body.customQuoteQuantity=5
  const original=body.quoteOptions[0]
  body.quoteOptions=[2,3].map((zone,i)=>({...original,id:`au-${zone}`,country:'澳大利亚',countryCode:'AU',quoteRegion:`澳大利亚${zone}区`,
    quote1Usd:20+i*10,quote2Usd:40+i*10,quote3Usd:60+i*10,quoteCustomUsd:100+i*10,
    logisticsInput:{...original.logisticsInput,country:'澳大利亚',zoneName:`澳大利亚${zone}区`},
    freightCny:Math.round((original.logisticsInput.weightKg*(zone===2?58:61)+10)*100)/100}))
  const prices=[[20,40,60,100],[30,50,70,110]], system=mode==='equal'?[25,45,65,105]:[27,47,67,107]
  body.customerQuote={quantities:[1,2,3,5],rows:body.quoteOptions.map((o,i)=>({optionId:o.id,prices:prices[i]})),averagePlans:[{
    id:'au-average',mode,display:'details',provider:'Combined Shipping',shippingTime:'8-15 workingdays',quantities:[1,2,3,5],
    members:body.quoteOptions.map((o,i)=>({optionId:o.id,weight:mode==='equal'?1:(i===0?30:70),sourcePrices:prices[i]})),systemPrices:system,prices:system
  }]}
  body.systemQuantityQuotes={quantities:[1,2,3,5],rows:structuredClone(body.customerQuote.rows)}
  return body
}
let record
await check('AU equal average persisted and identical for employee finance admin',async()=>{
  const body=averagePayload(); record=await employee.get('/quotations',post(body))
  assert.deepEqual(record.customerQuote.averagePlans[0].systemPrices,[25,45,65,105])
  for(const s of [employee,finance,admin]) assert.deepEqual(await s.get('/quotations/'+record.id),record)
  for(const s of [peer,purchase,logistics]) assert.equal((await s.raw('/quotations/'+record.id)).status,403)
})
await check('AU weighted average 30/70 persists independently from equal plan',async()=>{
  const saved=await employee.get('/quotations',post(averagePayload('weighted')))
  assert.deepEqual(saved.customerQuote.averagePlans[0].systemPrices,[27,47,67,107])
})
for(const [name,mutate] of [
  ['forged source price',b=>b.customerQuote.averagePlans[0].members[0].sourcePrices[0]=1],
  ['forged average result',b=>b.customerQuote.averagePlans[0].systemPrices[0]=1],
  ['duplicate member',b=>b.customerQuote.averagePlans[0].members[1]=structuredClone(b.customerQuote.averagePlans[0].members[0])],
  ['hidden source',b=>b.customerQuote.hiddenOptionIds=['au-2']],
  ['zero member weight',b=>b.customerQuote.averagePlans[0].members[0].weight=0],
  ['missing source price',b=>b.quoteOptions[0].quote1Usd=null],
  ['mixed tax scope',b=>b.quoteOptions[0].taxFeeMode='exempt'],
  ['weight sum not 100',b=>{b.customerQuote.averagePlans[0].mode='weighted'}],
  ['duplicate quantities',b=>b.customerQuote.quantities=[1,1,3,5]],
  ['negative customer amount',b=>b.customerQuote.rows[0].prices[0]=-1],
]) await check('reject '+name,async()=>{const body=averagePayload();mutate(body);assert.equal((await employee.raw('/quotations',post(body))).status,422)})
await check('employee cannot review and competing reviewer cannot steal claim',async()=>{
  const body={action:'claim',_version:record._version,_reviewVersion:record._reviewVersion}
  assert.equal((await employee.raw(`/quotations/${record.id}/finance-review`,{method:'PATCH',body})).status,403)
  record=await finance.get(`/quotations/${record.id}/finance-review`,{method:'PATCH',body})
  assert.equal(record.financeReviewStatus,'reviewing')
  assert.equal((await admin.raw(`/quotations/${record.id}/finance-review`,{method:'PATCH',body})).status,409)
  record=await finance.get(`/quotations/${record.id}/finance-review`,{method:'PATCH',body:{action:'complete',_version:record._version,_reviewVersion:record._reviewVersion,financeReviewStatus:'approved'}})
  assert.equal(record.financeReviewStatus,'approved')
  assert.deepEqual(await employee.get('/quotations/'+record.id),await finance.get('/quotations/'+record.id))
})
await check('customer average edit invalidates review but preserves system and first sheet',async()=>{
  const originalSystem=structuredClone(record.systemQuantityQuotes),originalSheet=structuredClone(record.sheetQuote)
  const customer=structuredClone(record.customerQuote); customer.averagePlans[0].prices[0]=26.28
  record=await employee.get('/quotations/'+record.id,{method:'PATCH',body:{_version:record._version,customerQuote:customer}})
  assert.equal(record.financeReviewStatus,'pending'); assert.equal(record.customerQuote.averagePlans[0].prices[0],26.28)
  assert.deepEqual(record.systemQuantityQuotes,originalSystem); assert.deepEqual(record.sheetQuote,originalSheet)
  assert.equal((await employee.raw('/quotations/'+record.id,{method:'PATCH',body:{_version:record._version,systemQuantityQuotes:customer}})).status,422)
})
await check('changed finance and purchase settings never rewrite saved quotation',async()=>{
  const before=await employee.get('/quotations/'+record.id), exchange=await finance.get('/finance-settings/exchange-rate')
  let changed
  try {
    changed=await finance.get('/finance-settings/exchange-rate',{method:'PUT',headers:{'If-Match':String(exchange._version)},body:{...exchange.value,usdCny:7.15}})
    const sku='PERF-SKU-08001', product=await purchase.get('/purchase-products/'+sku)
    await purchase.get('/purchase-products/'+sku,{method:'PUT',body:{...product,weightG:149.1234}})
    assert.deepEqual(await admin.get('/quotations/'+record.id),before)
    for(const s of [admin,employee,purchase,finance]) assert.equal((await s.get('/purchase-products/'+sku)).weightG,149.1234)
  } finally { if(changed) await finance.get('/finance-settings/exchange-rate',{method:'PUT',headers:{'If-Match':String(changed._version)},body:exchange.value}) }
})
await check('legacy invoice pending rejects new quotation at API despite zero tax',async()=>{
  const sku='DEEP-LEGACY-'+Date.now()
  await purchase.get('/purchase-products/'+sku,{method:'PUT',body:{sku,dataSource:'legacy_2026',weightG:100,minOrderQty:1,singleFreightCny:1,purchasePriceCny:10,taxPoint:0,invoiceType:'待确认',taxDifference:'待确认'}})
  const body=averagePayload();body.primarySku=sku
  const r=await employee.raw('/quotations',post(body)); assert.equal(r.status,422); assert.match(r.message,/待确认|票/)
})
if(process.env.PERF_UI_RECORD_ID) await check('real UI quotation withdraw stale-tab protection and resubmit preserve identity',async()=>{
  const before=await employee.get('/quotations/'+process.env.PERF_UI_RECORD_ID)
  const draft=await employee.get(`/quotations/${before.id}/withdraw`,post({_version:before._version,draft:{schemaVersion:2,quoteMode:'single',skuSearch:before.primarySku,customerName:before.customerName}}))
  assert.equal(draft.sourceQuote.id,before.id)
  assert.equal((await employee.raw('/quotations',post(averagePayload()))).status,409)
  assert.equal((await employee.raw('/quotation-drafts/mine/state',{method:'PUT',headers:{'If-Match':String(draft.version)},body:draft.payload})).status,409)
  const body=structuredClone(before)
  for(const key of Object.keys(body)) if(key.startsWith('_')||key.startsWith('financeReview')||key.startsWith('lifecycle')||['id','no','createdAt','updatedAt','revisions','quoteConfirmed','quoteConfirmedAt','quoteConfirmedBy'].includes(key))delete body[key]
  const settings=await employee.get('/finance-settings')
  body.financeVersions=Object.fromEntries(['country-classification','channel-policies','customer-grades','exchange-rate','tax-settings','surcharge-settings','customer-operation-fees'].map(k=>[k,settings[k]?._version??-1]))
  const product=await employee.get('/purchase-products/'+before.primarySku)
  body.purchaseVersions={[before.primarySku]:product._version+':'+product._updatedAt}
  body.logisticsRevision=(await employee.get('/logistics/published/manifest')).revision
  const stale=await employee.raw(`/quotations/${before.id}/resubmit`,post({_version:draft.sourceQuote.version,draftVersion:draft.version-1,quotation:body}))
  assert.equal(stale.status,409)
  const after=await employee.get(`/quotations/${before.id}/resubmit`,post({_version:draft.sourceQuote.version,draftVersion:draft.version,quotation:body}))
  assert.equal(after.id,before.id);assert.equal(after.no,before.no);assert.equal(after.lifecycleState,'active')
  assert.deepEqual(after.customerQuote,before.customerQuote);assert.equal((await employee.get('/quotation-drafts/mine/state')).exists,false)
})
const report={baseUrl,finishedAt:new Date().toISOString(),cases,passed:cases.every(c=>c.passed)}
await writeFile(process.env.PERF_OUTPUT||'artifacts/deep-regression.json',JSON.stringify(report,null,2))
if(!report.passed)process.exitCode=1
