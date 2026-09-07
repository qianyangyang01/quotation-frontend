import { writeFile } from 'node:fs/promises'
import { performance } from 'node:perf_hooks'
import { buildQuotationPayload, performanceProductNumber, performanceSku } from './quotation-payload.mjs'

const baseUrl = String(process.env.PERF_BASE_URL || 'http://127.0.0.1:18088').replace(/\/$/, '')
const users = Math.max(1, Number(process.env.PERF_USERS || 30))
const durationSeconds = Math.max(1, Number(process.env.PERF_DURATION_SECONDS || 600))
const warmupSeconds = Math.max(0, Number(process.env.PERF_WARMUP_SECONDS || 60))
// Only the measured workload is read-only; fixture preflight still writes locally.
const readOnly = process.env.PERF_READ_ONLY === 'true'
const password = process.env.PERF_PASSWORD || 'PerfAdmin123!'
const output = process.env.PERF_OUTPUT || 'artifacts/performance-result.json'
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(baseUrl)) throw new Error('压力测试仅允许独立本地环境')
let logisticsRevision = ''

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

function percentile(sorted, value) {
  if (!sorted.length) return 0
  return sorted[Math.min(sorted.length - 1, Math.max(0, Math.ceil(sorted.length * value) - 1))]
}

const samples = new Map()
const failures = []
let total = 0
let warm = true
function record(operation, elapsed, error) {
  if (warm) return
  total += 1
  if (error) failures.push({ operation, message: String(error.message || error) })
  else {
    const values = samples.get(operation) || []
    values.push(elapsed)
    samples.set(operation, values)
  }
}

async function operation(session, sequence) {
  const value = performanceProductNumber(sequence)
  const sku = performanceSku(value)
  const roll = (sequence * 37) % 100
  if (readOnly && roll >= 90) return ['sku-query', () => session.request(`/purchase-products/${sku}`)]
  if (roll < 40) return ['sku-query', () => session.request(`/purchase-products/${sku}`)]
  if (roll < 58) return ['purchase-list', () => session.request(`/purchase-products?q=${encodeURIComponent(sku)}&page=0&size=20`)]
  if (roll < 73) return ['logistics-query', async () => {
    const manifest = await session.request('/logistics/published/manifest')
    return session.request(`/logistics/published/rules?revision=${manifest.revision}&attribute=${encodeURIComponent('普货')}&country=美国`)
  }]
  if (roll < 90) return ['quotation-list', () => session.request('/quotations?scope=mine&page=0&size=50')]
  const bundle = roll % 2 === 0
  return [bundle ? 'quotation-save-bundle' : 'quotation-save-single', () => session.request('/quotations', {
    method: 'POST', headers: { 'Idempotency-Key': `perf:${session.account}:${sequence}:${crypto.randomUUID()}` },
    body: buildQuotationPayload(session.account, sequence, bundle, logisticsRevision),
  })]
}

const sessions = []
// Approve the deterministic isolated fixture using independently specified expected totals.
const setup = new Session('PERFADMIN'); await setup.login()
const fixtureId = '33333333-3333-4333-8333-333333333333'
const acceptance = await setup.request(`/logistics/rebuild/versions/${fixtureId}/billing-acceptance`)
if (!acceptance.quoteReady) {
  const samples = []
  for (const [country, zoneName, price, fee] of [['US', '', 48, 8], ['AU', '澳大利亚1区', 55, 10], ['AU', '澳大利亚2区', 58, 10], ['AU', '澳大利亚3区', 61, 10], ['AU', '澳大利亚4区', 64, 10]]) {
    for (const weightKg of [1, 2]) samples.push({ input: { country, zoneName, weightKg }, expectedTotal: weightKg * price + fee, sourceReference: 'seed.sql independent per-kg price and ticket fee' })
  }
  samples.push({ input: { country: 'US', weightKg: 31 }, expectRejected: true, sourceReference: 'seed.sql maximum 30kg' })
  await setup.request(`/logistics/rebuild/versions/${fixtureId}/billing-acceptance`, { method: 'POST', headers: { 'Idempotency-Key': `perf-accept-${crypto.randomUUID()}` }, body: { fingerprint: acceptance.fingerprint, engineVersion: acceptance.engineVersion, reviewConfirmed: true, sourceReference: 'seed.sql isolated independent fixture', note: 'Isolated performance fixture independent calculation', samples } })
}
for (let index = 0; index < users; index += 1) {
  const session = new Session(`PERF${String(index + 1).padStart(2, '0')}`)
  await session.login()
  sessions.push(session)
}

logisticsRevision = (await sessions[0].request('/logistics/published/manifest')).revision
try {
  await sessions[0].request('/quotations', {
    method: 'POST', headers: { 'Idempotency-Key': `perf:preflight:single:${crypto.randomUUID()}` },
    body: buildQuotationPayload(sessions[0].account, 0, false, logisticsRevision),
  })
  await sessions[0].request('/quotations', {
    method: 'POST', headers: { 'Idempotency-Key': `perf:preflight:bundle:${crypto.randomUUID()}` },
    body: buildQuotationPayload(sessions[0].account, 1, true, logisticsRevision),
  })
} catch (error) {
  throw new Error(`性能夹具契约失败：${String(error?.message || error)}`, { cause: error })
}

const startedAt = new Date().toISOString()
const warmupEnds = performance.now() + warmupSeconds * 1000
const ends = warmupEnds + durationSeconds * 1000
async function worker(session, offset) {
  let sequence = offset * 10_000_000
  while (performance.now() < ends) {
    if (warm && performance.now() >= warmupEnds) warm = false
    const [name, run] = await operation(session, sequence++)
    const started = performance.now()
    try { await run(); record(name, performance.now() - started) }
    catch (error) { record(name, performance.now() - started, error) }
    await new Promise(resolve => setTimeout(resolve, 250))
  }
}
await Promise.all(sessions.map(worker))

const operations = Object.fromEntries([...samples].map(([name, values]) => {
  values.sort((a, b) => a - b)
  return [name, {
    count: values.length,
    p50Ms: Number(percentile(values, 0.50).toFixed(2)),
    p95Ms: Number(percentile(values, 0.95).toFixed(2)),
    p99Ms: Number(percentile(values, 0.99).toFixed(2)),
    maxMs: Number((values.at(-1) || 0).toFixed(2)),
  }]
}))
const failureRate = total ? failures.length / total : 1
const queryNames = ['sku-query', 'purchase-list', 'logistics-query', 'quotation-list']
const thresholdFailures = queryNames.filter(name => (operations[name]?.p95Ms ?? Number.POSITIVE_INFINITY) > 1000)
for (const name of ['quotation-save-single', 'quotation-save-bundle']) {
  if (!readOnly && (operations[name]?.p95Ms ?? Number.POSITIVE_INFINITY) > 1500) thresholdFailures.push(name)
}
if (failureRate >= 0.01) thresholdFailures.push('error-rate')
const report = {
  baseUrl, startedAt, finishedAt: new Date().toISOString(), users, warmupSeconds, durationSeconds, readOnly, total,
  failures: failures.length, failureRate: Number(failureRate.toFixed(6)), operations,
  thresholds: { queryP95Ms: 1000, saveP95Ms: 1500, maxErrorRate: 0.01, passed: thresholdFailures.length === 0, failed: thresholdFailures },
  failureSamples: failures.slice(0, 20),
}
await writeFile(output, `${JSON.stringify(report, null, 2)}\n`)
process.stdout.write(`${JSON.stringify(report, null, 2)}\n`)
if (!report.thresholds.passed) process.exitCode = 1
