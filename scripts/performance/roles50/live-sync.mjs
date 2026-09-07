import { writeFile } from 'node:fs/promises'
import { buildQuotationPayload } from './quotation-payload.mjs'
const base = process.env.PERF_BASE_URL || 'http://127.0.0.1:18098'
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(base)) throw new Error('Only isolated loopback is allowed')
const adding = process.env.PERF_PHASE === 'adding'
const duration = Number(process.env.PERF_DURATION_SECONDS || 120)
const warmup = 10
const runId = Date.now().toString(36).toUpperCase()
class Session {
  constructor(account) { this.account = account; this.cookies = new Map() }
  async request(path, method = 'GET', body) {
    const headers = { Accept: 'application/json', Cookie: [...this.cookies].map(([k,v]) => `${k}=${v}`).join('; ') }
    if (body) headers['Content-Type'] = 'application/json'
    if (this.csrf && method !== 'GET') headers[this.csrf.headerName] = this.csrf.token
    if (method === 'POST') headers['Idempotency-Key'] = crypto.randomUUID()
    const response = await fetch(base + '/api/v1' + path, { method, headers, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(20000) })
    for (const cookie of response.headers.getSetCookie()) { const pair=cookie.split(';')[0],i=pair.indexOf('='); this.cookies.set(pair.slice(0,i),pair.slice(i+1)) }
    const value = await response.json()
    if (!response.ok) throw new Error(`${method} ${path}: ${response.status} ${value.message}`)
    return value.data
  }
  async login() { this.csrf = await this.request('/auth/csrf'); await this.request('/auth/login','POST',{ account: this.account, password: process.env.PERF_PASSWORD || 'PerfAdmin123!' }) }
}
const sessions = []
for (let i=0;i<50;i++) { const s = new Session(`PERF${String(i+1).padStart(2,'0')}`); await s.login(); sessions.push(s) }
const bodies = []
for (let i=0;i<20;i++) {
  const body = buildQuotationPayload(sessions[i].account,i,false)
  const snapshot = await sessions[i].request('/quotation-sync?sku='+body.primarySku)
  body.purchaseVersions = snapshot.purchaseVersions
  body.logisticsRevision = snapshot.logisticsRevision
  body.logisticsSyncScope = 'selected'
  const option = body.quoteOptions[0]
  option.quoteRegion = ''
  option.logisticsSamples = [1,2,3].map(q => {
    const weightKg = Number((option.logisticsInput.weightKg*q).toFixed(3))
    return { input: { country: '美国', zoneName: '', weightKg }, total: Math.round((weightKg*48+8)*100)/100, etaMinDays: 6, etaMaxDays: 12 }
  })
  await sessions[i].request('/quotation-sync/logistics','POST',{ logisticsAttribute: body.logisticsAttribute, quoteOptions: body.quoteOptions })
  bodies.push(body)
}
const errors=[], samples=new Map(), newSkus=[]
const startedAt=new Date().toISOString(), start=performance.now()+warmup*1000, end=start+duration*1000
let added=0, newSkuReads=0
async function worker(s,i) {
  let sequence=0
  while(performance.now()<end) {
    const n=sequence++, body=bodies[i], step=n%5
    let name, action
    if(i<20) {
      if(step===0 && newSkus.length) {
        const sku=newSkus.at(-1); name='business-read-new-sku'
        action=async()=>{ const p=await s.request('/purchase-products/'+sku); if(p.sku!==sku||p.purchasePriceCny!==10)throw new Error('New SKU readback differs'); newSkuReads++ }
      } else if(step<2) { name='business-sku'; action=()=>s.request('/purchase-products/'+body.primarySku) }
      else if(step===2) { name='current-sku-version'; action=()=>s.request('/quotation-sync?sku='+body.primarySku) }
      else if(step===3) { name='selected-logistics-check'; action=()=>s.request('/quotation-sync/logistics','POST',{ logisticsAttribute:body.logisticsAttribute,quoteOptions:body.quoteOptions }) }
      else { name='quotation-save'; action=()=>s.request('/quotations','POST',{...body,customerName:`SYNC-${runId}-${i}-${n}`}) }
    } else if(i<48) {
      if(adding && n%2===0) {
        const sku=`SYNC-${runId}-${i}-${n}`; name='purchase-add'
        action=async()=>{ await s.request('/purchase-products/'+sku,'PUT',{sku,category:'服装',weightG:100,minOrderQty:1,purchasePriceCny:10,singleFreightCny:1,stockStatus:'有货'}); newSkus.push(sku); if(newSkus.length>1000)newSkus.shift(); added++ }
      } else { name='purchase-read'; action=()=>s.request('/purchase-products/PERF-SKU-00001') }
    } else { name='logistics-versions'; action=()=>s.request('/logistics/versions?page=0&size=20') }
    const began=performance.now()
    try { await action(); if(began>=start) { if(!samples.has(name))samples.set(name,[]);samples.get(name).push(performance.now()-began) } }
    catch(error) { if(began>=start)errors.push({name,message:error.message}) }
    await new Promise(resolve=>setTimeout(resolve,250))
  }
}
await Promise.all(sessions.map(worker))
const percentile=(v,p)=>v[Math.min(v.length-1,Math.ceil(v.length*p)-1)]
const operations=Object.fromEntries([...samples].map(([name,v])=>{v.sort((a,b)=>a-b);return [name,{count:v.length,p95Ms:+percentile(v,.95).toFixed(2),p99Ms:+percentile(v,.99).toFixed(2),maxMs:+v.at(-1).toFixed(2)}]}))
const passed=!errors.length&&(!adding||newSkuReads>0)&&Object.entries(operations).every(([name,v])=>v.p95Ms<(name.includes('save')||name.includes('add')?1000:500))
const report={base,phase:adding?'adding':'read-background',users:50,roleMix:{business:20,purchase:28,logistics:2},startedAt,finishedAt:new Date().toISOString(),warmupSeconds:warmup,durationSeconds:duration,addedIncludingWarmup:added,newSkuReadsIncludingWarmup:newSkuReads,total:[...samples.values()].reduce((sum,v)=>sum+v.length,0)+errors.length,failures:errors.length,operations,passed,errors:errors.slice(0,10)}
await writeFile(process.env.PERF_OUTPUT||'artifacts/live-sync-load.json',JSON.stringify(report,null,2))
console.log(JSON.stringify(report,null,2)); if(!passed)process.exitCode=1
