import { writeFile } from 'node:fs/promises'
import { performance } from 'node:perf_hooks'
import { buildQuotationPayload } from './quotation-payload.mjs'

const baseUrl = process.env.PERF_BASE_URL || 'http://127.0.0.1:18098'
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(baseUrl)) throw new Error('Only isolated loopback is allowed')
const runId = Date.now().toString(36)
class Session {
  constructor(account) { this.account = account; this.cookies = new Map() }
  async raw(path, { method = 'GET', body, headers = {}, csrf = true } = {}) {
    const h = { Accept: 'application/json', ...headers }
    h.Cookie = [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ')
    if (body !== undefined) h['Content-Type'] = 'application/json'
    if (csrf && this.csrf && method !== 'GET') h[this.csrf.headerName] = this.csrf.token
    const response = await fetch(`${baseUrl}/api/v1${path}`, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000) })
    for (const cookie of response.headers.getSetCookie()) { const pair = cookie.split(';')[0]; const i = pair.indexOf('='); this.cookies.set(pair.slice(0, i), pair.slice(i + 1)) }
    const envelope = await response.json().catch(() => ({}))
    return { status: response.status, data: envelope.data, code: envelope.code, message: envelope.message }
  }
  async get(path, init) { const result = await this.raw(path, init); if (result.status !== 200) throw new Error(`${path} ${result.status} ${result.message}`); return result.data }
  async login() { this.csrf = await this.get('/auth/csrf'); await this.get('/auth/login', { method: 'POST', body: { account: this.account, password: process.env.PERF_PASSWORD || 'PerfAdmin123!' } }) }
}
const admin = new Session('PERFADMIN'), employee = new Session('PERF01'), purchase = new Session('PERFPUR'), finance = new Session('PERFFIN'), logistics = new Session('PERFLOG')
for (const session of [admin, employee, purchase, finance, logistics]) await session.login()
const cases = []
async function burst(name, action, expected, validate = () => true) {
  const started = performance.now()
  const results = await Promise.all(Array.from({ length: 50 }, (_, i) => action(i).catch(error => ({ status: 0, message: error.message }))))
  const statuses = {}; for (const result of results) statuses[result.status] = (statuses[result.status] || 0) + 1
  const passed = results.every(r => expected.includes(r.status)) && validate(results)
  const record = { name, concurrency: 50, elapsedMs: Math.round(performance.now() - started), statuses, passed, unexpected: results.filter(r => !expected.includes(r.status)).slice(0, 3).map(r => ({ status: r.status, code: r.code, message: r.message })) }
  cases.push(record); process.stdout.write(`${JSON.stringify(record)}\n`)
  return results
}
const anonymous = new Session('anonymous')
await burst('unauthenticated-read', () => anonymous.raw('/purchase-products'), [401])
await burst('missing-product', () => employee.raw('/purchase-products/SOAK-NOT-EXISTS'), [404])
await burst('employee-cannot-write-finance', () => employee.raw('/finance-settings/exchange-rate', { method: 'PUT', headers: { 'If-Match': '-1' }, body: { usdCny: 7 } }), [403])
await burst('employee-cannot-write-purchase', () => employee.raw(`/purchase-products/NOAUTH-${runId}`, { method: 'PUT', body: { weightG: 100 } }), [403])
await burst('purchase-cannot-manage-logistics', () => purchase.raw('/logistics/providers'), [403])
await burst('missing-csrf-write', () => purchase.raw(`/purchase-products/CSRF-${runId}`, { method: 'PUT', csrf: false, body: { weightG: 100 } }), [403])
await burst('negative-purchase-cost', () => purchase.raw(`/purchase-products/NEG-${runId}`, { method: 'PUT', body: { purchasePriceCny: -1 } }), [422])
await burst('invalid-quotation', () => employee.raw('/quotations', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: {} }), [422])
const revision = (await employee.get('/logistics/published/manifest')).revision
await burst('stale-logistics-revision', i => employee.raw('/quotations', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: buildQuotationPayload('PERF01', i, false, 'stale-revision') }), [409])
await burst('tampered-freight', i => {
  const body = buildQuotationPayload('PERF01', i, false, revision); body.quoteOptions[0].freightCny += 100
  return employee.raw('/quotations', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body })
}, [409, 422])
const sku = `RACE-${runId}`
const product = await purchase.get(`/purchase-products/${sku}`, { method: 'PUT', body: { weightG: 100, minOrderQty: 1, purchasePriceCny: 10 } })
await burst('purchase-optimistic-concurrent-edit', i => purchase.raw(`/purchase-products/${sku}`, { method: 'PUT', body: { ...product, purchasePriceCny: 20 + i } }), [200, 409], results => results.filter(r => r.status === 200).length === 1)
const exchange = await finance.get('/finance-settings/exchange-rate')
await burst('finance-optimistic-concurrent-edit', i => finance.raw('/finance-settings/exchange-rate', { method: 'PUT', headers: { 'If-Match': String(exchange._version) }, body: { ...exchange.value, updatedAt: `${runId}-${i}` } }), [200, 409], results => results.filter(r => r.status === 200).length === 1)
const afterExchange = await finance.get('/finance-settings/exchange-rate')
await finance.get('/finance-settings/exchange-rate', { method: 'PUT', headers: { 'If-Match': String(afterExchange._version) }, body: exchange.value })
const key = `duplicate-${runId}`
const payload = buildQuotationPayload('PERF01', 7000, false, revision)
const duplicate = await burst('same-key-concurrent-quotation', () => employee.raw('/quotations', { method: 'POST', headers: { 'Idempotency-Key': key }, body: payload }), [200, 409], results => results.some(r => r.status === 200) && new Set(results.filter(r => r.status === 200).map(r => r.data.id)).size === 1)
const replay = await employee.get('/quotations', { method: 'POST', headers: { 'Idempotency-Key': key }, body: payload })
cases.push({ name: 'idempotent-replay-same-record', passed: duplicate.some(r => r.status === 200 && r.data.id === replay.id) })
await burst('same-key-different-body', () => employee.raw('/quotations', { method: 'POST', headers: { 'Idempotency-Key': key }, body: { ...payload, customerName: 'different' } }), [409])
await burst('other-owner-cannot-edit-quotation', () => purchase.raw(`/quotations/${replay.id}`, { method: 'PATCH', body: { _version: replay._version, status: 'lost' } }), [403])
const providerBody = { name: `异常测试-${runId}`, code: `RACE-${runId}`, enabled: false }
await burst('duplicate-logistics-provider-code', () => logistics.raw('/logistics/providers', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: providerBody }), [200, 409], results => results.filter(r => r.status === 200).length === 1)
const draft = await employee.get('/quotation-drafts/mine/state')
await burst('draft-concurrent-edit', i => employee.raw('/quotation-drafts/mine/state', { method: 'PUT', headers: { 'If-Match': String(draft.exists ? draft.version : -1) }, body: { schemaVersion: 2, customerName: `${runId}-${i}`, quoteMode: 'single' } }), [200, 409], results => results.filter(r => r.status === 200).length === 1)
const peer = new Session('PERF02'); await peer.login();
await burst('peer-employee-cannot-edit-other-owner', () => peer.raw(`/quotations/${replay.id}`, {method:'PATCH',body:{_version:replay._version,status:'lost'}}), [403]);
await burst('company-scope-stays-own-records', () => peer.raw('/quotations?scope=company&page=0&size=100'), [200], rs=>rs.every(r=>r.data.items.every(x=>x.salespersonAccount==='PERF02')));
for (const [role,s] of [['employee',employee],['purchase',purchase],['logistics',logistics]]) {
 await burst(`${role}-cannot-list-users`,()=>s.raw('/users'),[403]);
 await burst(`${role}-cannot-create-admin`,()=>s.raw('/users',{method:'POST',body:{account:'FORBIDDEN',name:'Blocked fixture',role:'super_admin',password:'FixtureOnly123!'}}),[403]);
}
await burst('logistics-cannot-edit-purchase',()=>logistics.raw('/purchase-products/DENIED',{method:'PUT',body:{weightG:100}}),[403]);
await burst('logistics-cannot-read-quotes',()=>logistics.raw('/quotations'),[403]);
await burst('purchase-cannot-read-quotes',()=>purchase.raw('/quotations'),[403]);
await burst('employee-cannot-read-logistics-admin',()=>employee.raw('/logistics/providers'),[403]);
for(const role of ['purchase','employee','logistics']) {
 const account=`INIT${role.slice(0,3)}${runId}`.toUpperCase();
 await admin.get('/users',{method:'POST',body:{account,name:'Isolated initial password fixture',role,password:'FixtureOnly123!'}});
 const s=new Session(account); s.csrf=await s.get('/auth/csrf');
 const before=await s.get('/auth/login',{method:'POST',body:{account,password:'FixtureOnly123!'}});
 const path=role==='purchase'?'/purchase-products':role==='employee'?'/quotations':'/logistics/providers';
 const blocked=await s.raw(path);
 await s.get('/auth/change-password',{method:'POST',body:{currentPassword:'FixtureOnly123!',newPassword:'ChangedFixture123!'}});
 const after=await s.get('/auth/me'); const allowed=await s.raw(path);
 cases.push({name:`${role}-initial-password-gate`,passed:before.mustChangePassword===true&&blocked.status===428&&blocked.code==='PASSWORD_CHANGE_REQUIRED'&&after.mustChangePassword===false&&allowed.status===200,permissions:after.permissions,blockedStatus:blocked.status,afterStatus:allowed.status});
}
const report = { baseUrl, finishedAt: new Date().toISOString(), cases, passed: cases.every(c => c.passed) }
await writeFile(process.env.PERF_OUTPUT || 'artifacts/performance/abnormal-50.json', JSON.stringify(report, null, 2))
if (!report.passed) process.exitCode = 1
