import assert from 'node:assert/strict'
export const base = process.env.PERF_BASE_URL || 'http://127.0.0.1:18249'
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(base)) throw Error('Only an isolated loopback stack is allowed')
export const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
export let observe = () => {}
export function setObserver(fn) { observe = fn }
export let active = 0
export let peak = 0
export function resetPeak() { peak = active }
export class Session {
  constructor(account) { this.account = account; this.cookies = new Map() }
  async request(path, { method = 'GET', body, headers = {}, allow = [], label = path.split('?')[0], binary = false } = {}) {
    const h = { Accept: 'application/json', ...headers }
    if (this.cookies.size) h.Cookie = [...this.cookies].map(([k,v]) => `${k}=${v}`).join('; ')
    if (body !== undefined && !(body instanceof FormData)) h['Content-Type'] = 'application/json'
    if (this.csrf && method !== 'GET') h[this.csrf.headerName] = this.csrf.token
    const started = performance.now(); active++; peak = Math.max(peak, active)
    let status = 0, bytes = 0, failure
    try {
      const response = await fetch(base + '/api/v1' + path, { method, headers: h,
        body: body instanceof FormData ? body : body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30_000) })
      status = response.status
      for (const cookie of response.headers.getSetCookie()) {
        const pair = cookie.split(';')[0], i = pair.indexOf('='); this.cookies.set(pair.slice(0,i), pair.slice(i+1))
      }
      if (binary && response.ok) { const result = new Uint8Array(await response.arrayBuffer()); bytes=result.length; return result }
      const text = await response.text(); bytes = Buffer.byteLength(text)
      let envelope
      try { envelope=JSON.parse(text) } catch { throw Error(`${method} ${path}: invalid JSON (${status})`) }
      if (allow.includes(status)) return { expectedStatus: status, message: envelope.message, data: envelope.data }
      if (!response.ok) throw Object.assign(Error(`${method} ${path}: ${status} ${envelope.message}`), { status, detail:envelope })
      return envelope.data
    } catch(error) { failure=error; throw error }
    finally { active--; observe({account:this.account,role:this.role,label,method,status,bytes,ms:performance.now()-started,error:failure?.message,expected:allow.includes(status)}) }
  }
  async login() {
    this.csrf = await this.request('/auth/csrf', {label:'login-csrf'})
    this.user = await this.request('/auth/login', {method:'POST',body:{account:this.account,password:process.env.PERF_PASSWORD||'PerfAdmin123!'},label:'login'})
    assert(this.user.account === this.account && this.user.name.startsWith('隔离测试'), 'This is not the seeded isolated account')
    this.role = this.user.role
  }
}
function canonical(value){return JSON.stringify(value,(_key,v)=>v&&typeof v==='object'&&!Array.isArray(v)?Object.fromEntries(Object.keys(v).sort().map(k=>[k,v[k]])):v)}
export const priceSnapshot = row => canonical({primarySku:row.primarySku,quoteMode:row.quoteMode,systemQuoteUsd:row.systemQuoteUsd,systemQuoteCny:row.systemQuoteCny,totalCostCny:row.totalCostCny,exchangeRate:row.exchangeRate,weightSnapshot:row.weightSnapshot,quoteOptions:row.quoteOptions})
export function cleanQuotation(row) {
  const body=structuredClone(row)
  for(const key of Object.keys(body)) if (key.startsWith('_')||key.startsWith('financeReview')||key.startsWith('lifecycle')||['id','no','createdAt','updatedAt','revisions','quoteConfirmed','quoteConfirmedAt','quoteConfirmedBy'].includes(key)) delete body[key]
  return body
}
export async function refreshVersions(session, body) {
  const settings=await session.request('/finance-settings',{label:'finance-read'})
  body.financeVersions=Object.fromEntries(Object.entries(settings).map(([key,value])=>[key,value._version]))
  body.logisticsRevision=(await session.request('/logistics/published/manifest',{label:'logistics-manifest'})).revision
  const skus=[...new Set(body.primarySku.split(/[、,+\s]+/).filter(Boolean))]
  body.purchaseVersions=Object.fromEntries(await Promise.all(skus.map(async sku=>{const row=await session.request('/purchase-products/'+encodeURIComponent(sku),{label:'product-detail'});return [sku,row._version+':'+row._updatedAt]})))
  return body
}
