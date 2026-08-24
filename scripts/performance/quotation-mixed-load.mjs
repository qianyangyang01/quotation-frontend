import { mkdir, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { performance } from 'node:perf_hooks'
import { randomUUID } from 'node:crypto'

function option(name, fallback) {
  const index = process.argv.indexOf(`--${name}`)
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback
}

const baseUrl = option('base-url', 'http://127.0.0.1:18088').replace(/\/$/, '')
const usersCount = Number(option('users', '20'))
const durationSeconds = Number(option('duration', '120'))
const rampMilliseconds = Number(option('ramp-ms', '150'))
const mode = option('mode', 'mixed')
const outputPath = resolve(option('output', `artifacts/load-test-${Date.now()}.json`))
const runId = option('run-id', new Date().toISOString().replace(/\D/g, '').slice(0, 14))
const accountPrefix = option('account-prefix', `mx${runId.slice(-8)}`).slice(0, 16)
const sku = option('sku', 'LOAD-SKU-001')

const requiredEnvironment = [
  'LOAD_ADMIN_INITIAL_PASSWORD',
  'LOAD_ADMIN_PASSWORD',
  'LOAD_USER_INITIAL_PASSWORD',
  'LOAD_USER_PASSWORD',
]
for (const name of requiredEnvironment) {
  if (!process.env[name]) throw new Error(`Missing required environment variable: ${name}`)
}

class HttpError extends Error {
  constructor(message, status, body) {
    super(message)
    this.status = status
    this.body = body
  }
}

class Session {
  constructor(label) {
    this.label = label
    this.cookies = new Map()
    this.csrf = null
  }

  cookieHeader() {
    return [...this.cookies.entries()].map(([name, value]) => `${name}=${value}`).join('; ')
  }

  acceptCookies(headers) {
    const values = typeof headers.getSetCookie === 'function'
      ? headers.getSetCookie()
      : [headers.get('set-cookie')].filter(Boolean)
    for (const value of values) {
      const pair = String(value).split(';', 1)[0]
      const separator = pair.indexOf('=')
      if (separator > 0) this.cookies.set(pair.slice(0, separator), pair.slice(separator + 1))
    }
  }

  async refreshCsrf() {
    const response = await this.raw('/api/v1/auth/csrf')
    const envelope = await response.json()
    if (!response.ok) throw new HttpError('Unable to obtain CSRF token', response.status, envelope)
    this.csrf = envelope.data
  }

  async raw(path, { method = 'GET', body, headers = {}, csrf = true } = {}) {
    const upperMethod = method.toUpperCase()
    if (csrf && !['GET', 'HEAD', 'OPTIONS'].includes(upperMethod) && !this.csrf) await this.refreshCsrf()
    const requestHeaders = new Headers(headers)
    if (!requestHeaders.has('Accept')) requestHeaders.set('Accept', 'application/json')
    requestHeaders.set('X-Request-Id', `mixed-load:${runId}:${randomUUID()}`)
    const cookie = this.cookieHeader()
    if (cookie) requestHeaders.set('Cookie', cookie)
    if (body !== undefined && !requestHeaders.has('Content-Type')) requestHeaders.set('Content-Type', 'application/json')
    if (csrf && !['GET', 'HEAD', 'OPTIONS'].includes(upperMethod)) {
      requestHeaders.set(this.csrf.headerName, this.csrf.token)
    }
    const response = await fetch(`${baseUrl}${path}`, {
      method: upperMethod,
      headers: requestHeaders,
      body: body === undefined ? undefined : JSON.stringify(body),
      redirect: 'manual',
    })
    this.acceptCookies(response.headers)
    return response
  }

  async json(path, init) {
    const response = await this.raw(path, init)
    const text = await response.text()
    let body
    try { body = text ? JSON.parse(text) : null } catch { body = { raw: text.slice(0, 500) } }
    if (!response.ok) throw new HttpError(body?.message || `HTTP ${response.status}`, response.status, body)
    return body?.data
  }

  async login(account, password) {
    await this.json('/api/v1/auth/login', { method: 'POST', body: { account, password } })
  }
}

function sleep(milliseconds) {
  return new Promise(resolvePromise => setTimeout(resolvePromise, milliseconds))
}

function percentile(values, fraction) {
  if (!values.length) return 0
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * fraction) - 1)]
}

function summarise(values) {
  if (!values.length) return { count: 0, avgMs: 0, p50Ms: 0, p95Ms: 0, p99Ms: 0, maxMs: 0 }
  const average = values.reduce((sum, value) => sum + value, 0) / values.length
  return {
    count: values.length,
    avgMs: Number(average.toFixed(2)),
    p50Ms: Number(percentile(values, 0.5).toFixed(2)),
    p95Ms: Number(percentile(values, 0.95).toFixed(2)),
    p99Ms: Number(percentile(values, 0.99).toFixed(2)),
    maxMs: Number(Math.max(...values).toFixed(2)),
  }
}

async function setupUsers() {
  const admin = new Session('setup-admin')
  await admin.login('admin', process.env.LOAD_ADMIN_INITIAL_PASSWORD)
  await admin.json('/api/v1/auth/change-password', {
    method: 'POST',
    body: {
      currentPassword: process.env.LOAD_ADMIN_INITIAL_PASSWORD,
      newPassword: process.env.LOAD_ADMIN_PASSWORD,
    },
  })

  const definitions = Array.from({ length: usersCount }, (_, index) => ({
    account: `${accountPrefix}${String(index + 1).padStart(2, '0')}`,
    name: `混压用户${String(index + 1).padStart(2, '0')}`,
    role: index % 10 === 0 ? 'finance' : 'employee',
  }))

  for (const definition of definitions) {
    await admin.json('/api/v1/users', {
      method: 'POST',
      body: { ...definition, password: process.env.LOAD_USER_INITIAL_PASSWORD },
    })
  }

  const sessions = []
  for (const definition of definitions) {
    const session = new Session(definition.account)
    await session.login(definition.account, process.env.LOAD_USER_INITIAL_PASSWORD)
    await session.json('/api/v1/auth/change-password', {
      method: 'POST',
      body: {
        currentPassword: process.env.LOAD_USER_INITIAL_PASSWORD,
        newPassword: process.env.LOAD_USER_PASSWORD,
      },
    })
    sessions.push({ ...definition, session, latest: null })
  }
  return sessions
}

function quotationBody(user, sequence) {
  const value = 10 + (sequence % 50)
  return {
    customerName: `混压客户-${runId}-${user.account}-${sequence}`,
    primarySku: sku,
    productSummary: '混合压测标准商品',
    productCategory: '混压测试',
    quoteMode: 'single',
    country: 'US',
    carrier: 'LOAD-CARRIER',
    channel: 'LOAD-CHANNEL',
    rule: 'LOAD-RULE',
    customerGrade: 'A',
    taxCustomerType: 'A',
    exchangeRate: 7.2,
    totalCostCny: value * 7.2,
    systemQuoteUsd: value * 1.25,
    systemQuoteCny: value * 9,
    customQuoteQuantity: 1,
    quoteOptions: [{ id: `option-${sequence}`, label: '标准方案', country: 'US', channel: 'LOAD-CHANNEL', quantity: 1, unitPriceUsd: value * 1.25 }],
    revisions: [],
    loadTest: { runId, virtualUser: user.account, sequence },
  }
}

const durations = new Map()
const failures = []
let attempts = 0
let successes = 0

async function measured(operation, action) {
  const started = performance.now()
  attempts += 1
  try {
    const result = await action()
    const elapsed = performance.now() - started
    if (!durations.has(operation)) durations.set(operation, [])
    durations.get(operation).push(elapsed)
    successes += 1
    return result
  } catch (error) {
    const elapsed = performance.now() - started
    if (!durations.has(`${operation}:failed`)) durations.set(`${operation}:failed`, [])
    durations.get(`${operation}:failed`).push(elapsed)
    if (failures.length < 50) failures.push({
      operation,
      account: error.account,
      status: error.status || 0,
      message: error.message,
      code: error.body?.code,
      requestId: error.body?.requestId,
    })
    throw error
  }
}

async function createQuotation(user, sequence) {
  const result = await measured('create', () => user.session.json('/api/v1/quotations', {
    method: 'POST',
    headers: { 'Idempotency-Key': `mixed-load:${runId}:${user.account}:${sequence}:${randomUUID()}` },
    body: quotationBody(user, sequence),
  }))
  user.latest = result
}

async function runUser(user, deadline, startDelay) {
  let sequence = 0
  await sleep(startDelay)
  await createQuotation(user, sequence++)
  if (mode === 'burst') return
  while (performance.now() < deadline) {
    const dice = Math.random()
    try {
      if (dice < 0.65) {
        const scope = user.role === 'finance' ? 'company' : 'mine'
        await measured('query', () => user.session.json(`/api/v1/quotations?scope=${scope}&page=0&size=50`))
      } else if (dice < 0.85) {
        await createQuotation(user, sequence++)
      } else if (dice < 0.95 && user.latest) {
        user.latest = await measured('update', () => user.session.json(`/api/v1/quotations/${user.latest.id}`, {
          method: 'PATCH',
          body: { status: 'pending', note: `混压更新 ${runId} ${sequence++}`, _version: user.latest._version },
        }))
      } else if (user.latest) {
        await measured('pdf', async () => {
          const response = await user.session.raw(`/api/v1/quotations/${user.latest.id}/pdf`, {
            headers: { Accept: 'application/pdf' },
          })
          if (!response.ok) {
            const body = await response.text()
            throw new HttpError(`PDF HTTP ${response.status}`, response.status, { raw: body.slice(0, 500) })
          }
          await response.arrayBuffer()
        })
      }
    } catch (error) {
      error.account = user.account
    }
    await sleep(250 + Math.floor(Math.random() * 500))
  }
}

async function main() {
  if (!Number.isInteger(usersCount) || usersCount < 1 || usersCount > 100) throw new Error('users must be between 1 and 100')
  if (!['burst', 'mixed'].includes(mode)) throw new Error('mode must be burst or mixed')
  if (!Number.isFinite(durationSeconds) || durationSeconds < 5) throw new Error('duration must be at least 5 seconds')
  if (!Number.isFinite(rampMilliseconds) || rampMilliseconds < 0 || rampMilliseconds > 10_000) throw new Error('ramp-ms must be between 0 and 10000')

  const health = await fetch(`${baseUrl}/api/v1/auth/csrf`)
  if (!health.ok) throw new Error(`UAT endpoint unavailable: HTTP ${health.status}`)
  const users = await setupUsers()

  const startedAt = new Date().toISOString()
  const started = performance.now()
  const deadline = started + durationSeconds * 1000
  await Promise.all(users.map((user, index) => runUser(user, deadline, index * rampMilliseconds)))
  const elapsedSeconds = (performance.now() - started) / 1000

  const operations = Object.fromEntries([...durations.entries()].map(([name, values]) => [name, summarise(values)]))
  const errorCount = attempts - successes
  const errorRate = attempts ? errorCount / attempts : 1
  const report = {
    runId,
    startedAt,
    completedAt: new Date().toISOString(),
    target: { baseUrl, users: usersCount, durationSeconds, rampMilliseconds, mode, mix: mode === 'mixed' ? { query: 0.65, create: 0.20, update: 0.10, pdf: 0.05 } : { create: 1 } },
    actual: {
      elapsedSeconds: Number(elapsedSeconds.toFixed(2)),
      attempts,
      successes,
      errors: errorCount,
      errorRate: Number(errorRate.toFixed(6)),
      requestsPerSecond: Number((attempts / elapsedSeconds).toFixed(2)),
    },
    operations,
    thresholds: mode === 'burst'
      ? {
          zeroErrors: errorCount === 0,
          createP95Below2000Ms: (operations.create?.p95Ms ?? Infinity) < 2000,
        }
      : {
          zeroErrors: errorCount === 0,
          queryP95Below1000Ms: (operations.query?.p95Ms ?? Infinity) < 1000,
          createP95Below2000Ms: (operations.create?.p95Ms ?? Infinity) < 2000,
          updateP95Below2000Ms: (operations.update?.p95Ms ?? Infinity) < 2000,
          pdfP95Below5000Ms: (operations.pdf?.p95Ms ?? Infinity) < 5000,
        },
    failures,
  }
  await mkdir(dirname(outputPath), { recursive: true })
  await writeFile(outputPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8')
  process.stdout.write(`${JSON.stringify(report, null, 2)}\n`)
  if (!Object.values(report.thresholds).every(Boolean)) process.exitCode = 2
}

await main()
