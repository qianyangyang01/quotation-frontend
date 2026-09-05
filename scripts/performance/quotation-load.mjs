import { writeFile } from 'node:fs/promises'
import { performance } from 'node:perf_hooks'
import { buildQuotationPayload, performanceProductNumber, performanceSku } from './quotation-payload.mjs'

const baseUrl = String(process.env.PERF_BASE_URL || 'http://127.0.0.1:18088').replace(/\/$/, '')
const users = Math.max(1, Number(process.env.PERF_USERS || 30))
const durationSeconds = Math.max(1, Number(process.env.PERF_DURATION_SECONDS || 600))
const warmupSeconds = Math.max(0, Number(process.env.PERF_WARMUP_SECONDS || 60))
// Only the measured workload is read-only; fixture preflight still writes locally.
const readOnly = process.env.PERF_READ_ONLY === 'true'
const roleMix = process.env.PERF_ROLE_MIX === 'true'
const runId = Date.now().toString(36)
const password = process.env.PERF_PASSWORD || 'PerfAdmin123!'
const output = process.env.PERF_OUTPUT || 'artifacts/performance-result.json'
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(baseUrl)) throw new Error('压力测试仅允许独立本地环境')
if (roleMix && (users !== 50 || readOnly)) throw new Error('多角色场景要求50用户且PERF_READ_ONLY=false，包含隔离写入')
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
const minuteSamples = new Map()
const failures = []
let total = 0
let warm = true
function record(operation, elapsed, error) {
  if (warm) return
  total += 1
  const minute = Math.max(0, Math.floor((performance.now() - warmupEnds) / 60_000))
  const bucket = minuteSamples.get(minute) || { total: 0, failures: 0, operations: new Map() }
  bucket.total += 1
  if (error) bucket.failures += 1
  else {
    const values = bucket.operations.get(operation) || []
    values.push(elapsed)
    bucket.operations.set(operation, values)
  }
  minuteSamples.set(minute, bucket)
  if (error) failures.push({ operation, message: String(error.message || error) })
  else {
    const values = samples.get(operation) || []
    values.push(elapsed)
    samples.set(operation, values)
  }
}

async function operation(session, sequence) {
  if (roleMix && session.role !== 'employee') return roleOperation(session, sequence)
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

async function roleOperation(session, sequence) {
  const step = sequence % 10
  if (session.role === 'purchase') {
    if (step < 5) return ['purchase-role-search', () => session.request(`/purchase-products?q=PERF-SKU-${String(sequence % 100).padStart(3, '0')}&page=0&size=50`)]
    if (step < 8) return ['purchase-role-detail', () => session.request('/purchase-products/PERF-SKU-00001')]
    if (step === 8) return ['purchase-role-stats', () => session.request('/purchase-products/stats')]
    const sku = `SOAK-${runId}-${session.account}-${sequence}`.toUpperCase()
    return ['purchase-create-verify', async () => {
      const saved = await session.request(`/purchase-products/${sku}`, { method: 'PUT', body: { sku, category: '服装', weightG: 100, minOrderQty: 1, purchasePriceCny: 10, singleFreightCny: 1, stockStatus: '有货', quotationOwner: session.account } })
      const result = await session.request(`/purchase-products/${sku}`)
      if (saved.sku !== sku || result.purchasePriceCny !== 10) throw new Error('采购新增读回不一致')
    }]
  }
  if (session.role === 'finance') {
    if (step < 7) return ['finance-settings-read', () => session.request('/finance-settings')]
    if (step < 9) return ['finance-quotation-list', () => session.request('/quotations?scope=company&page=0&size=50')]
    const key = ['exchange-rate', 'customer-grades', 'tax-settings'][session.roleIndex]
    return ['finance-save-verify', async () => {
      const before = await session.request(`/finance-settings/${key}`)
      const saved = await session.request(`/finance-settings/${key}`, { method: 'PUT', headers: { 'If-Match': String(before._version) }, body: before.value })
      if (JSON.stringify(saved.value) !== JSON.stringify(before.value)) throw new Error('财务保存读回不一致')
    }]
  }
  if (step < 4) return ['logistics-role-channels', () => session.request('/logistics/channels?page=0&size=50')]
  if (step < 8) return ['logistics-role-providers', () => session.request('/logistics/providers?page=0&size=50')]
  if (step === 8) return ['logistics-role-versions', () => session.request('/logistics/versions?page=0&size=20')]
  return ['logistics-provider-add', () => session.request('/logistics/providers', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: { name: `隔离测试-${session.account}-${sequence}`, code: `SOAK-${runId}-${session.account}-${sequence}`, enabled: false } })]
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
  session.role = !roleMix || index < 40 ? 'employee' : index < 44 ? 'purchase' : index < 47 ? 'finance' : 'logistics'
  session.roleIndex = index - 44
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
if (roleMix) queryNames.push('purchase-role-search', 'purchase-role-detail', 'purchase-role-stats', 'finance-settings-read', 'finance-quotation-list', 'logistics-role-channels', 'logistics-role-providers', 'logistics-role-versions')
const thresholdFailures = queryNames.filter(name => (operations[name]?.p95Ms ?? Number.POSITIVE_INFINITY) > 1000)
for (const name of ['quotation-save-single', 'quotation-save-bundle']) {
  if (!readOnly && (operations[name]?.p95Ms ?? Number.POSITIVE_INFINITY) > 1500) thresholdFailures.push(name)
}
if (failureRate >= 0.01) thresholdFailures.push('error-rate')
if (roleMix) for (const name of ['purchase-create-verify', 'finance-save-verify', 'logistics-provider-add']) {
  if ((operations[name]?.p95Ms ?? Infinity) > 1500) thresholdFailures.push(name)
}
const report = {
  baseUrl, startedAt, finishedAt: new Date().toISOString(), users, warmupSeconds, durationSeconds, readOnly, roleMix, total,
  failures: failures.length, failureRate: Number(failureRate.toFixed(6)), operations,
  thresholds: { queryP95Ms: 1000, saveP95Ms: 1500, maxErrorRate: 0.01, passed: thresholdFailures.length === 0, failed: thresholdFailures },
  failureSamples: failures.slice(0, 20),
  minutes: [...minuteSamples].map(([minute, bucket]) => ({
    minute, total: bucket.total, failures: bucket.failures,
    operations: Object.fromEntries([...bucket.operations].map(([name, values]) => {
      values.sort((a, b) => a - b)
      return [name, { count: values.length, p95Ms: Number(percentile(values, 0.95).toFixed(2)), p99Ms: Number(percentile(values, 0.99).toFixed(2)) }]
    })),
  })),
}
await writeFile(output, `${JSON.stringify(report, null, 2)}\n`)
process.stdout.write(`${JSON.stringify(report, null, 2)}\n`)
if (!report.thresholds.passed) process.exitCode = 1
