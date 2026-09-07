import { readFile, readdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
const base = 'http://127.0.0.1:28088'
const corpus = process.env.LOGISTICS_CORPUS
const output = process.env.LOGISTICS_ACCEPTANCE_OUTPUT
if (!corpus || !output || !process.env.LOGISTICS_TEST_PASSWORD) throw new Error('Missing local acceptance settings')
const cookies = new Map()
let csrf
async function request(route, method = 'GET', body, key = crypto.randomUUID()) {
  const headers = { Accept: 'application/json', Cookie: [...cookies].map(([k, v]) => `${k}=${v}`).join('; ') }
  if (method !== 'GET') { headers[csrf.headerName] = csrf.token; headers['Idempotency-Key'] = key }
  if (body && !(body instanceof FormData)) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(body) }
  const response = await fetch(base + '/api/v1' + route, { method, headers, body, signal: AbortSignal.timeout(130000) })
  for (const value of response.headers.getSetCookie()) { const pair = value.split(';')[0]; const i = pair.indexOf('='); cookies.set(pair.slice(0, i), pair.slice(i + 1)) }
  const result = await response.json()
  if (!response.ok) throw new Error(`${route}: ${response.status} ${result.code} ${result.message}`)
  return result.data
}
const hash = async bytes => Buffer.from(await crypto.subtle.digest('SHA-256', bytes)).toString('hex')
csrf = await request('/auth/csrf')
const user = await request('/auth/login', 'POST', { account: 'VALIDATION', password: process.env.LOGISTICS_TEST_PASSWORD })
csrf = await request('/auth/csrf')
if (user.mustChangePassword) await request('/auth/change-password', 'POST', { currentPassword: process.env.LOGISTICS_TEST_PASSWORD, newPassword: process.env.LOGISTICS_TEST_PASSWORD + 'Changed' })
const dataset = await request('/logistics/rebuild/datasets', 'POST', { name: 'Isolated acceptance ' + Date.now() })
const samples = [], failures = []
async function probe() { const start = performance.now(); try { await request('/logistics/rebuild/datasets'); samples.push(performance.now() - start) } catch (error) { failures.push(error.message) } }
for (let i = 0; i < 10; i++) await probe()
const baseline = samples.splice(0)
const interval = setInterval(() => { void probe() }, 500)
try {
  const files = []
  for (const name of (await readdir(corpus)).filter(name => /\.xlsx?$/i.test(name)).sort()) {
    const bytes = await readFile(path.join(corpus, name)); files.push({ name, bytes, size: bytes.length, sha256: await hash(bytes) })
  }
  const started = performance.now()
  const session = await request(`/logistics/rebuild/datasets/${dataset.id}/uploads`, 'POST', { files: files.map(({ name, size, sha256 }) => ({ name, size, sha256 })), replaceDrafts: false })
  let next = 0
  async function send() {
    while (next < files.length) {
      const index = next++, file = files[index]
      for (let offset = 0; offset < file.size; offset += session.chunkBytes) {
        const bytes = file.bytes.subarray(offset, offset + session.chunkBytes)
        const form = new FormData(); form.append('chunk', new Blob([bytes]), 'chunk'); form.append('sha256', await hash(bytes))
        await request(`/logistics/rebuild/uploads/${session.id}/files/${index}/chunks/${offset / session.chunkBytes}`, 'POST', form)
      }
    }
  }
  await Promise.all([send(), send()])
  const uploadMs = performance.now() - started
  let batch = await request(`/logistics/rebuild/uploads/${session.id}/complete`, 'POST')
  const accepted = performance.now()
  while (['queued', 'processing'].includes(batch.status) && performance.now() - accepted < 360000) {
    await new Promise(resolve => setTimeout(resolve, 1500))
    batch = await request(`/logistics/rebuild/imports/${batch.id}`)
    console.log(JSON.stringify({ phase: batch.phase, files: batch.payload.processedFiles, channels: batch.payload.processedChannels }))
  }
  const p95 = values => values.sort((a, b) => a - b)[Math.ceil(values.length * .95) - 1]
  const report = { datasetId: dataset.id, batchId: batch.id, status: batch.status, uploadMs, importMs: performance.now() - accepted, files: batch.payload.processedFiles, apiP95Ms: p95(samples), baselineP95Ms: p95(baseline), failures, fileReports: batch.payload.fileReports?.map(({ fileName, status, message }) => ({ fileName, status, message })) }
  await writeFile(output, JSON.stringify(report, null, 2)); console.log(JSON.stringify(report))
  if (batch.status !== 'completed' || failures.length || report.apiP95Ms > 1000) process.exitCode = 1
} finally { clearInterval(interval) }
