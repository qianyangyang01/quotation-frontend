// Writes only to the explicitly isolated local QA instance. Never run against production.
import { readFile, readdir, mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { randomUUID, createHash } from 'node:crypto'
import assert from 'node:assert/strict'

const base = 'http://127.0.0.1:5519/api/v1'
const password = process.env.LOGISTICS_QA_PASSWORD
assert(password, 'Set LOGISTICS_QA_PASSWORD for the isolated QA administrator')
const corpus = process.env.LOGISTICS_CORPUS_DIR
assert(corpus, 'Set LOGISTICS_CORPUS_DIR to the original workbook folder')
const cookies = new Map()
let csrf
async function request(path, { method = 'GET', body, key, binary = false, expected = 200 } = {}) {
  const headers = { Cookie: [...cookies].map(([k, v]) => `${k}=${v}`).join('; ') }
  if (csrf && method !== 'GET') headers[csrf.headerName] = csrf.token
  if (key) headers['Idempotency-Key'] = key
  if (body && !(body instanceof FormData)) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(body) }
  const response = await fetch(base + path, { method, headers, body })
  for (const cookie of response.headers.getSetCookie()) { const [pair] = cookie.split(';'); const n = pair.indexOf('='); cookies.set(pair.slice(0, n), pair.slice(n + 1)) }
  if (response.status !== expected) throw Error(`${method} ${path}: ${response.status} ${(await response.text()).slice(0, 500)}`)
  if (expected !== 200) return null
  return binary ? Buffer.from(await response.arrayBuffer()) : (await response.json()).data
}
csrf = await request('/auth/csrf')
const me = await request('/auth/login', { method: 'POST', body: { account: 'ADMIN', password } })
assert.equal(me.name, '本地QA管理员', 'Refusing to mutate a non-QA account/instance')
assert.equal(me.mustChangePassword, false)
csrf = await request('/auth/csrf')
const report = { startedAt: new Date().toISOString(), checks: [] }
function passed(name, details = {}) { report.checks.push({ name, ...details }); console.log(JSON.stringify({ name, ...details })) }
const root = '/logistics/rebuild'
const createKey = `qa-${randomUUID()}`
const createBody = { name: `真实文件API验收-${Date.now()}` }
const [dataset, duplicate] = await Promise.all([1, 2].map(() => request(`${root}/datasets`, { method: 'POST', body: createBody, key: createKey })))
assert.equal(dataset.id, duplicate.id)
report.datasetId = dataset.id
passed('concurrent-dataset-create-idempotent')
const names = (await readdir(corpus)).filter(n => /\.xlsx?$/i.test(n) && !n.startsWith('~$')).sort()
assert.equal(names.length, 11)
async function upload(key) {
  const form = new FormData()
  for (const name of names) form.append('files', new Blob([await readFile(resolve(corpus, name))]), name)
  return request(`${root}/datasets/${dataset.id}/imports`, { method: 'POST', body: form, key })
}
const importKey = `qa-${randomUUID()}`
const started = performance.now()
const batch = await upload(importKey)
report.batchId = batch.id
const again = await upload(importKey)
assert.equal(again.id, batch.id)
passed('upload-idempotent', { files: names.length, submissionMs: Math.round(performance.now() - started) })
let finished = batch
for (let n = 0; n < 300 && ['queued', 'processing'].includes(finished.status); n++) {
  await new Promise(resolve => setTimeout(resolve, 1000))
  finished = await request(`${root}/imports/${batch.id}`)
}
assert.equal(finished.status, 'completed')
assert.equal(finished.payload.fileReports.length, names.length)
assert(finished.payload.fileReports.every(f => f.sourceEvidence?.sha256 && f.sheets.every(s => !s.sourceCells)))
const cellEvidence = await request(`${root}/imports/${batch.id}/files/0/evidence`, { binary: true })
assert.equal(createHash('sha256').update(cellEvidence).digest('hex'), finished.payload.fileReports[0].sourceEvidence.sha256)
assert(JSON.parse(cellEvidence).sheets.some(s => s.sourceCells?.length))
passed('source-cell-evidence-preserved-outside-progress-payload', { bytes: cellEvidence.length })
assert(finished.payload.results.some(r => r.status === 'draft'), 'At least one parsed channel must stage successfully')
assert(!finished.payload.results.some(r => /IllegalStateException|NullPointerException|BadSqlGrammar/.test(r.message || '')), 'Unexpected staging exception')
passed('real-files-durable-import', { elapsedMs: finished.payload.elapsedMs, parsingMs: finished.payload.parsingMs, stagingMs: finished.payload.stagingMs, results: finished.payload.results.length, drafts: finished.payload.results.filter(r => r.status === 'draft').length, blocked: finished.payload.results.filter(r => r.status === 'blocked').length })
const candidate = finished.payload.results.find(r => r.status === 'draft' && r.providerName === '花海') || finished.payload.results.find(r => r.status === 'draft')
const version = await request(`${root}/versions/${candidate.versionId}`)
assert.equal(version.basePublishedVersionId, '')
const reviewKey = `qa-${randomUUID()}`
const reviewPath = `${root}/channels/${candidate.channelId}/versions/${candidate.versionId}/review`
const reviewBody = { note: '隔离QA：核对价格管理，待适配渠道不开放报价', removalConfirmed: true, reviewConfirmed: true }
const [reviewed, replay] = await Promise.all([1, 2].map(() => request(reviewPath, { method: 'POST', body: reviewBody, key: reviewKey })))
assert.equal(reviewed.id, replay.id)
assert.equal(reviewed.status, 'published')
passed('per-channel-review-idempotent')
const largeCandidate = finished.payload.results.find(r => r.status === 'draft' && r.providerName === '递四方' && r.channelName.includes('标准挂号-普货'))
assert(largeCandidate, 'Real multi-page channel fixture is required')
const largeVersion = await request(`${root}/versions/${largeCandidate.versionId}`)
assert(largeVersion.rows.length > 50)
await review(largeVersion)
const prices = await request(`${root}/datasets/${dataset.id}/prices?size=1`)
assert.equal(prices.total, version.rows.length + largeVersion.rows.length)
assert.equal(prices.items.length, 1)
passed('multi-page-current-prices', { total: prices.total, pageSize: prices.size })
const output = resolve('backend/target/logistics-api-qa', dataset.id)
await mkdir(output, { recursive: true })
for (const [name, path] of [['prices.xlsx', `${root}/datasets/${dataset.id}/prices.xlsx`], ['changes.xlsx', `${root}/imports/${batch.id}/changes.xlsx`]]) {
  const start = performance.now(); const bytes = await request(path, { binary: true }); assert.equal(bytes.subarray(0, 2).toString(), 'PK')
  await writeFile(resolve(output, name), bytes)
  passed('server-export', { name, bytes: bytes.length, elapsedMs: Math.round(performance.now() - start) })
}
const original = await request(`${root}/imports/${batch.id}/files/0`, { binary: true })
const sha = bytes => createHash('sha256').update(bytes).digest('hex')
assert.equal(sha(original), sha(await readFile(resolve(corpus, names[0]))))
passed('original-file-sha256')
const active = (await request(`${root}/datasets`)).find(d => d.status === 'active')
assert.notEqual(active.id, dataset.id)
const unauthenticated = await fetch(base + `${root}/datasets/${dataset.id}/prices.xlsx`)
assert.equal(unauthenticated.status, 401)
passed('preparing-isolated-and-download-authenticated')
async function importOne(name, bytes, destination = dataset.id) {
  const form = new FormData(); form.append('files', new Blob([bytes]), name)
  let task = await request(`${root}/datasets/${destination}/imports`, { method: 'POST', body: form, key: `qa-${randomUUID()}` })
  for (let n = 0; n < 120 && ['queued', 'processing'].includes(task.status); n++) {
    await new Promise(resolve => setTimeout(resolve, 500)); task = await request(`${root}/imports/${task.id}`)
  }
  return task
}
const roundtrip = await importOne('价格重新导入.xlsx', await readFile(resolve(output, 'prices.xlsx')))
assert.equal(roundtrip.payload.results.length, 2)
assert(roundtrip.payload.results.every(r => r.status === 'unchanged'), JSON.stringify(roundtrip.payload.results))
passed('standard-export-reimport-no-new-version')
const corrupt = await importOne('损坏文件.xlsx', Buffer.from('not-an-excel-workbook'))
assert.equal(corrupt.status, 'failed')
assert.equal((await request(`${root}/datasets/${dataset.id}/prices?size=1`)).total, prices.total)
passed('damaged-file-does-not-replace-published-price')
await request(`${root}/datasets/${dataset.id}/backup`, { method: 'POST' })
const preview = await request(`${root}/datasets/${dataset.id}/preview`, { method: 'POST', body: { mappings: [] } })
assert.equal(preview.readyChannels, 0)
await request(`${root}/datasets/${dataset.id}/activate`, { method: 'POST', body: { previewToken: preview.previewToken, mappings: preview.mappings, note: 'QA：验证不允许零可报价渠道切换', reviewConfirmed: true, unavailableConfirmed: true }, key: `qa-${randomUUID()}`, expected: 422 })
passed('no-safe-channel-cannot-cut-over')
// Fully synthetic safe rates exercise cutover/update/rollback without adapting a real provider by guesswork.
const suffix = randomUUID().slice(0, 8)
const provider = await request('/logistics/providers', { method: 'POST', key: `qa-${randomUUID()}`, body: { name: `QA合成物流-${suffix}`, code: `QA-P-${suffix}` } })
const channel = await request('/logistics/channels', { method: 'POST', key: `qa-${randomUUID()}`, body: { providerId: provider.id, name: 'QA合成普货', code: `QA-C-${suffix}`, logisticsAttribute: '普货' } })
const synthetic = { areaName: '美国', countryCode: 'US', weightFromKg: 0, weightToKg: 1, pricePerKg: 50, registrationFee: 20, volumetric: false }
async function review(v, removalConfirmed = true) {
  return request(`${root}/channels/${v.channelId}/versions/${v.id}/review`, { method: 'POST', key: `qa-${randomUUID()}`, body: { note: '仅限本地QA合成测试数据', reviewConfirmed: true, removalConfirmed } })
}
const firstOld = await request(`/logistics/channels/${channel.id}/manual-draft`, { method: 'PUT', key: `qa-${randomUUID()}`, body: { rows: [synthetic, { ...synthetic, areaName: '德国', countryCode: 'DE' }] } })
await review(firstOld)
const originalSynthetic = await request(`${root}/datasets/${active.id}/prices.xlsx?versionId=${firstOld.id}`, { binary: true })
await writeFile(resolve(output, 'synthetic-prices.xlsx'), originalSynthetic)
const secondOld = await request(`/logistics/channels/${channel.id}/manual-draft`, { method: 'PUT', key: `qa-${randomUUID()}`, body: { rows: [{ ...synthetic, pricePerKg: 60 }] } })
await review(secondOld)
const updatedSynthetic = await request(`${root}/datasets/${active.id}/prices.xlsx?versionId=${secondOld.id}`, { binary: true })
const next = await request(`${root}/datasets`, { method: 'POST', key: `qa-${randomUUID()}`, body: { name: `QA合成切换-${suffix}` } })
const initialized = await importOne('合成完整价格.xlsx', originalSynthetic, next.id)
const initialResult = initialized.payload.results[0]
assert.equal(initialResult.status, 'draft', JSON.stringify(initialResult))
assert.equal(initialResult.quoteReady, false, 'Parsed or price-approved content is not a billing acceptance')
assert.notEqual(initialResult.channelId, channel.id)
const firstNew = await request(`${root}/versions/${initialResult.versionId}`)
assert.equal(firstNew.basePublishedVersionId, '')
await review(firstNew)
const billingPath = `${root}/versions/${firstNew.id}/billing-acceptance`
const unaccepted = await request(billingPath)
assert.equal(unaccepted.quoteReady, false)
assert.deepEqual(unaccepted.unsupportedReasons, [])
const billingBody = {
  fingerprint: unaccepted.fingerprint, engineVersion: unaccepted.engineVersion,
  note: 'QA合成规则人工核算：每公斤50元、挂号20元，不计泡，无附加条件',
  sourceReference: '脚本合成固定样本，不授权任何真实渠道', reviewConfirmed: true,
  samples: ['US', 'DE'].flatMap(country => [.2, .8].map(weightKg => ({
    sourceReference: '50 × 重量 + 20，独立常量预期', input: { country, weightKg, marks: ['普货'] }, expectedTotal: weightKg === .2 ? 30 : 60,
  }))).concat([{ sourceReference: '大于1kg不收寄', input: { country: 'US', weightKg: 2, marks: ['普货'] }, expectRejected: true }]),
}
await request(billingPath, { method: 'POST', body: { ...billingBody, fingerprint: 'stale' }, key: randomUUID(), expected: 409 })
const billingKey = randomUUID()
const accepted = await Promise.all([1, 2].map(() => request(billingPath, { method: 'POST', body: billingBody, key: billingKey })))
assert.equal(accepted[0].records.length, 1)
assert.deepEqual(accepted[0], accepted[1])
assert.equal(accepted[0].quoteReady, true)
passed('server-billing-acceptance-fingerprint-and-concurrent-idempotency')
const requiredPath = `${root}/datasets/${next.id}/required-channels`
const emptyList = await request(requiredPath)
assert.equal(emptyList.confirmed, false)
assert.equal(emptyList.channelIds.length, 0)
await request(`${root}/datasets/${next.id}/backup`, { method: 'POST' })
const beforeSelection = await request(`${root}/datasets/${next.id}/preview`, { method: 'POST', body: { mappings: [] } })
assert.equal(beforeSelection.readyChannels, 1)
assert.equal(beforeSelection.requiredReady, false)
await request(`${root}/datasets/${next.id}/activate`, { method: 'POST', body: { ...beforeSelection, note: 'QA：一个可报价渠道不是切换门槛', reviewConfirmed: true, unavailableConfirmed: true }, key: randomUUID(), expected: 422 })
const requiredBody = { revision: emptyList.revision, confirmed: true, channelIds: [firstNew.channelId], note: '仅选择QA合成测试渠道，不替用户确认真实必用清单' }
const requiredKey = randomUUID()
const selections = await Promise.all([1, 2].map(() => request(requiredPath, { method: 'PUT', body: requiredBody, key: requiredKey })))
assert.equal(selections[0].revision, 1)
assert.deepEqual(selections[0], selections[1])
await request(requiredPath, { method: 'PUT', body: requiredBody, key: randomUUID(), expected: 409 })
await request(`${root}/datasets/${next.id}/activate`, { method: 'POST', body: { ...beforeSelection, note: 'QA：旧预览不可切换', reviewConfirmed: true, unavailableConfirmed: true }, key: randomUUID(), expected: 409 })
passed('required-selection-revision-idempotency-and-stale-cutover')
await request(`${root}/datasets/${next.id}/backup`, { method: 'POST' })
const cutover = await request(`${root}/datasets/${next.id}/preview`, { method: 'POST', body: { mappings: [] } })
const activationKey = `qa-${randomUUID()}`
const activationBody = { previewToken: cutover.previewToken, mappings: cutover.mappings.map(({ oldChannelId, newChannelId }) => ({ oldChannelId, newChannelId })), note: '仅本地QA合成库，禁止用于生产', reviewConfirmed: true, unavailableConfirmed: true }
const switched = await Promise.all([1, 2].map(() => request(`${root}/datasets/${next.id}/activate`, { method: 'POST', body: activationBody, key: activationKey })))
assert.equal(switched[0].targetDatasetId, switched[1].targetDatasetId)
assert.equal((await request(`${root}/datasets`)).find(d => d.status === 'active').id, next.id)
assert.equal((await request(`${root}/datasets`)).find(d => d.id === active.id).status, 'archived')
passed('synthetic-cutover-new-identities-archive-and-concurrent-replay', { targetDatasetId: next.id })
const update = await importOne('合成完整价格.xlsx', updatedSynthetic, next.id)
const updateVersion = await request(`${root}/versions/${update.payload.results[0].versionId}`)
assert.equal(updateVersion.basePublishedVersionId, firstNew.id)
assert.equal(updateVersion.summary.removed, 1)
assert(updateVersion.diffRows.some(d => d.changes.some(c => c.delta === 10)))
await request(`${root}/channels/${updateVersion.channelId}/versions/${updateVersion.id}/review`, { method: 'POST', key: `qa-${randomUUID()}`, body: { note: 'QA验证移除必须确认', removalConfirmed: false, reviewConfirmed: true }, expected: 422 })
await review(updateVersion)
assert.equal((await request(`${root}/versions/${updateVersion.id}/billing-acceptance`)).quoteReady, false)
passed('new-price-version-invalidates-previous-billing-acceptance')
const rollback = await request(`/logistics/channels/${firstNew.channelId}/versions/${firstNew.id}/rollback`, { method: 'POST', key: `qa-${randomUUID()}`, body: { note: 'QA回滚验证' } })
assert.equal(rollback.rows.length, 2)
const afterRollback = await importOne('合成完整价格.xlsx', updatedSynthetic, next.id)
assert.equal(afterRollback.payload.results[0].status, 'draft')
const reapplied = await request(`${root}/versions/${afterRollback.payload.results[0].versionId}`)
assert.equal(reapplied.basePublishedVersionId, rollback.id)
await review(reapplied)
const unchangedAgain = await importOne('合成完整价格.xlsx', updatedSynthetic, next.id)
assert.equal(unchangedAgain.payload.results[0].status, 'unchanged')
passed('price-diff-removal-confirmation-rollback-and-reimport')
report.finishedAt = new Date().toISOString()
await writeFile(resolve(output, 'report.json'), JSON.stringify(report, null, 2))
await writeFile(resolve(output, 'batch.json'), JSON.stringify(finished, null, 2))
console.log(`QA_REPORT=${resolve(output, 'report.json')}`)
