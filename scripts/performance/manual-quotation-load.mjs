// Real HTTP regression and sustained load for the isolated quotation_perf fixture.
import { readFile, writeFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import { performance } from 'node:perf_hooks'
const baseUrl = process.env.PERF_BASE_URL || 'http://127.0.0.1:18105'
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(baseUrl)) throw new Error('Only isolated loopback fixtures are allowed')
const password = process.env.PERF_PASSWORD || 'PerfAdmin123!'
const root = process.env.PERF_FIXTURE_DIR || 'artifacts/performance/manual-release'
const payloads = JSON.parse(await readFile(root + '/payloads.json','utf8'))
const originals = JSON.parse(await readFile(root + '/saved.json','utf8'))
const users = Number(process.env.PERF_USERS || 30)
const warmupSeconds = Number(process.env.PERF_WARMUP_SECONDS || 60)
const durationSeconds = Number(process.env.PERF_DURATION_SECONDS || 600)
class Session {
  constructor(account) { this.account = account; this.cookies = new Map(); this.csrf = null }
  absorb(response) {
    const values = typeof response.headers.getSetCookie === 'function' ? response.headers.getSetCookie() : [response.headers.get('set-cookie')].filter(Boolean)
    for (const value of values) {
      const first = value.split(';', 1)[0]
      const separator = first.indexOf('=')
      if (separator > 0) this.cookies.set(first.slice(0, separator), first.slice(separator + 1))
    }
  }
  cookieHeader() { return [...this.cookies].map(([key, value]) => `${key}=${value}`).join('; ') }
  async request(path, { method = 'GET', body, headers = {} } = {}) {
    const requestHeaders = { Accept: 'application/json', 'X-Request-Id': crypto.randomUUID(), ...headers }
    if (this.cookies.size) requestHeaders.Cookie = this.cookieHeader()
    if (body !== undefined) requestHeaders['Content-Type'] = 'application/json'
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && this.csrf) requestHeaders[this.csrf.headerName] = this.csrf.token
    const response = await fetch(`${baseUrl}/api/v1${path}`, { method, headers: requestHeaders, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30_000) })
    this.absorb(response)
    const envelope = await response.json().catch(() => null)
    if (!response.ok) throw new Error(`${method} ${path}: HTTP ${response.status} ${envelope?.code || ''} ${envelope?.message || ''}`)
    return envelope?.data
  }
  async login() {
    this.csrf = await this.request('/auth/csrf')
    await this.request('/auth/login', { method: 'POST', body: { account: this.account, password } })
  }
}


const modes = ['freight-trial','shipping-only','single','bundle']
let checkedPrices = 0
function verify(record, mode) {
  const p = payloads[mode]
  assert.equal(record.quoteMode, mode)
  const qs = [1,2,3,7]
  const expected = qs.map(q => {
    const pieces = q * (mode === 'bundle' ? 3 : 1)
    const manual = mode === 'freight-trial' || mode === 'shipping-only'
    const grams = (manual ? 500 : 104) * pieces
    const freight = Math.floor((grams * 48 + 5) / 10) + 800
    const cost = freight + pieces * (mode === 'shipping-only' ? 0 : mode === 'freight-trial' ? 3000 : 934)
    // Integer cents oracle: fixed tax rounds to cents, surcharge then ceil to USD 0.05.
    return Math.ceil((Math.round(cost / 5 + 30) + 20) / 5) * 5 / 100
  })
  assert.deepEqual(record.systemQuantityQuotes.quantities, qs)
  assert.deepEqual(record.systemQuantityQuotes.rows[0].prices, expected)
  assert.equal(record.systemQuoteUsd, expected[0])
  assert.equal(Math.round(record.systemQuoteCny*100), Math.round(expected[0]*600))
  assert.equal(record.totalCostCny, p.totalCostCny)
  assert.deepEqual(record.manualPricing, p.manualPricing)
  const o = record.quoteOptions[0]
  assert.deepEqual([o.quote1Usd,o.quote2Usd,o.quote3Usd],expected.slice(0,3))
  assert.equal(o.totalCostCny, p.quoteOptions[0].totalCostCny)
  assert.equal(o.freightCny, p.quoteOptions[0].freightCny)
  assert.deepEqual(o.logisticsSamples, p.quoteOptions[0].logisticsSamples)
  checkedPrices += qs.length
}
function body(mode, account) {
  const p=structuredClone(payloads[mode]); p.salespersonAccount=account;
  p.customerName='QA-ISOLATED-LOAD'; return p
}
const sessions=[]
for(let i=0;i<users;i++) { const s=new Session('PERF'+String(i+1).padStart(2,'0')); await s.login(); sessions.push(s) }
const setup=sessions[0]
for(const mode of modes) verify(originals[mode],mode)
const historyBefore=Object.fromEntries(await Promise.all(modes.map(async mode=>[mode,await setup.request('/quotations/'+originals[mode].id)])))
const exceptions=[]
async function reject(name,mode,mutate) {
  const p=body(mode,setup.account); mutate(p)
  let error
  try { await setup.request('/quotations',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:p}) }
  catch(e) { error=e }
  assert.ok(error && /HTTP (400|409|422)/.test(error.message), name+': '+(error?.message||'unexpected acceptance'))
  exceptions.push({name,result:error.message})
}
for (const [name,mode,mutate] of [
 ['zero weight','freight-trial',p=>p.manualPricing.weightGrams=0],
 ['negative weight','freight-trial',p=>p.manualPricing.weightGrams=-1],
 ['excess weight precision','freight-trial',p=>p.manualPricing.weightGrams=500.0001],
 ['negative cost','freight-trial',p=>p.manualPricing.costCny=-1],
 ['excess cost precision','freight-trial',p=>p.manualPricing.costCny=30.001],
 ['shipping cost injection','shipping-only',p=>p.manualPricing.costCny=1],
 ['packaging injection','shipping-only',p=>p.quoteOptions[0].logisticsInput.packagingWeightKg=.1],
 ['freight mismatch','freight-trial',p=>p.quoteOptions[0].freightCny+=.01],
 ['sample freight mismatch','shipping-only',p=>p.quoteOptions[0].logisticsSamples[3].total+=.01],
 ['sample weight mismatch','freight-trial',p=>p.quoteOptions[0].logisticsSamples[3].input.weightKg+=.001],
 ['missing quantity sample','freight-trial',p=>p.quoteOptions[0].logisticsSamples.pop()],
 ['stale finance','freight-trial',p=>p.financeVersions['exchange-rate']=999],
 ['stale logistics','shipping-only',p=>p.logisticsRevision='stale'],
 ['invalid commission','freight-trial',p=>p.commissionThreshold=0],
 ['missing manual input','freight-trial',p=>delete p.manualPricing],
 ['root cost mismatch','freight-trial',p=>p.totalCostCny+=.01],
]) await reject(name,mode,mutate)
const key=crypto.randomUUID(); const repeatBody=body('freight-trial',setup.account)
const repeated=await Promise.all(Array.from({length:10},()=>setup.request('/quotations',{method:'POST',headers:{'Idempotency-Key':key},body:repeatBody})))
assert.equal(new Set(repeated.map(r=>r.id)).size,1)
for(const r of repeated) verify(r,'freight-trial')
console.log(JSON.stringify({preflight:'passed',exceptions:exceptions.length,idempotentConcurrent:10,checkedPrices}))
const samples=new Map(),failures=[];let total=0,sequence=0
const startedAt=new Date().toISOString(),warmEnd=performance.now()+warmupSeconds*1000,end=warmEnd+durationSeconds*1000
async function worker(s,index) {
 let local=index*1000000
 while(performance.now()<end) {
  const n=local++,roll=(n*37)%100,mode=modes[(index+Math.floor(n/100))%4]
  const sku='PERF-SKU-'+String(n%10000+1).padStart(5,'0')
  let name,run
  if(roll<40) {name='sku-query';run=()=>s.request('/purchase-products/'+sku)}
  else if(roll<58) {name='purchase-list';run=()=>s.request('/purchase-products?q='+sku+'&page=0&size=20')}
  else if(roll<73) {name='logistics-query';run=async()=>{ const m=await s.request('/logistics/published/manifest'); return s.request('/logistics/published/rules?revision='+m.revision+'&attribute='+encodeURIComponent('普货')+'&country='+encodeURIComponent('美国')) }}
  else if(roll<90) {name='quotation-list';run=()=>s.request('/quotations?scope=mine&page=0&size=50')}
  else {name='save-read-'+mode;run=async()=>{
   const r=await s.request('/quotations',{method:'POST',headers:{'Idempotency-Key':'load-'+crypto.randomUUID()},body:body(mode,s.account)})
   verify(r,mode);verify(await s.request('/quotations/'+r.id),mode)
  }}
  const start=performance.now(),measured=start>=warmEnd
  try {await run();if(measured){total++;const a=samples.get(name)||[];a.push(performance.now()-start);samples.set(name,a)}}
  catch(e) {if(measured)total++;failures.push({name,message:e.message,measured})}
  if(index===0 && ++sequence%100===0)console.log(JSON.stringify({elapsedSeconds:Math.round((performance.now()-(warmEnd-warmupSeconds*1000))/1000),total,failures:failures.length,checkedPrices}))
  await new Promise(resolve=>setTimeout(resolve,250))
 }
}
await Promise.all(sessions.map(worker))
for(const mode of modes) assert.deepEqual(await setup.request('/quotations/'+originals[mode].id),historyBefore[mode])
const operations=Object.fromEntries([...samples].map(([name,a])=>{a.sort((x,y)=>x-y);const pct=p=>Number(a[Math.min(a.length-1,Math.ceil(a.length*p)-1)].toFixed(2));return [name,{count:a.length,p50Ms:pct(.5),p95Ms:pct(.95),p99Ms:pct(.99),maxMs:Number(a.at(-1).toFixed(2))}]}))
const failed=Object.entries(operations).filter(([name,s])=>s.p95Ms>(name.startsWith('save-')?1500:1000)).map(([name])=>name)
if(failures.length)failed.push('errors-or-price-mismatch')
for(const mode of modes)if(!operations['save-read-'+mode])failed.push('missing-'+mode)
const report={startedAt,finishedAt:new Date().toISOString(),baseUrl,users,warmupSeconds,durationSeconds,total,checkedPrices,exceptions,idempotentConcurrent:10,unchangedHistoricalRecords:modes.length,operations,failures,passed:failed.length===0,failed}
await writeFile(root+'/load-result.json',JSON.stringify(report,null,2))
console.log(JSON.stringify(report,null,2));if(!report.passed)process.exitCode=1
