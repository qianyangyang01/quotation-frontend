// Runs only against the fixed, isolated local QA service. No production overrides.
import assert from 'node:assert/strict'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { basename, resolve } from 'node:path'
import { randomUUID, createHash } from 'node:crypto'

const base = 'http://127.0.0.1:5519/api/v1', root = '/logistics/rebuild'
assert(process.env.LOGISTICS_QA_PASSWORD && process.env.LOGISTICS_TEMPLATE_FILE)
const cookies = new Map()
let csrf
async function request(path, { method = 'GET', body, binary = false } = {}) {
  const headers = { Cookie: [...cookies].map(([k, v]) => `${k}=${v}`).join('; ') }
  if (method !== 'GET') { headers[csrf.headerName] = csrf.token; headers['Idempotency-Key'] = randomUUID() }
  if (body && !(body instanceof FormData)) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(body) }
  const r = await fetch(base + path, { method, headers, body })
  for (const cookie of r.headers.getSetCookie()) { const pair = cookie.split(';')[0], i = pair.indexOf('='); cookies.set(pair.slice(0, i), pair.slice(i + 1)) }
  assert.equal(r.status, 200, `${method} ${path}: ${r.status}`)
  return binary ? Buffer.from(await r.arrayBuffer()) : (await r.json()).data
}
csrf = await request('/auth/csrf')
const me = await request('/auth/login', { method: 'POST', body: { account: 'ADMIN', password: process.env.LOGISTICS_QA_PASSWORD } })
assert.equal(me.name, '本地QA管理员', 'Refusing to write to an unverified environment')
csrf = await request('/auth/csrf')
const source = resolve(process.env.LOGISTICS_TEMPLATE_FILE), bytes = await readFile(source)
const sha = b => createHash('sha256').update(b).digest('hex')
const report = { startedAt: new Date().toISOString(), source, sha256: sha(bytes) }
const dataset = await request(`${root}/datasets`, { method: 'POST', body: { name: `必用渠道待用户确认-${Date.now()}` } })
report.datasetId = dataset.id
report.url = `http://127.0.0.1:5519/quotation/logistics?dataset=${dataset.id}&logisticsTab=imports`
const form = new FormData(); form.append('files', new Blob([bytes]), basename(source))
const start = performance.now()
let batch = await request(`${root}/datasets/${dataset.id}/imports`, { method: 'POST', body: form })
report.uploadMs = Math.round(performance.now() - start); report.batchId = batch.id
for (let i = 0; i < 600 && ['queued', 'processing'].includes(batch.status); i++) {
  await new Promise(resolve => setTimeout(resolve, 500))
  batch = await request(`${root}/imports/${batch.id}`)
}
assert.equal(batch.status, 'completed')
assert.equal(batch.payload.results.length, 88)
assert(batch.payload.results.every(r => r.status === 'draft'))
const queryStart = performance.now(), list = await request(`${root}/datasets/${dataset.id}/required-channels`)
report.checklistQueryMs = Math.round(performance.now() - queryStart)
assert.equal(list.channels.length, 88); assert.equal(list.channelIds.length, 0); assert.equal(list.confirmed, false)
assert.equal(list.channels.reduce((n, c) => n + c.priceRows, 0), 3094)
assert(list.channels.every(c => c.quoteReady === false))
assert.equal(report.sha256, sha(await readFile(source)))
const evidence = await request(`${root}/imports/${batch.id}/files/0/evidence`, { binary: true })
assert.equal(sha(evidence), batch.payload.fileReports[0].sourceEvidence.sha256)
assert(JSON.parse(evidence).sheets.some(s => s.sourceCells?.length))
Object.assign(report, { elapsedMs: batch.payload.elapsedMs, parsingMs: batch.payload.parsingMs, stagingMs: batch.payload.stagingMs, progressPayloadBytes: Buffer.byteLength(JSON.stringify(batch)), evidenceBytes: evidence.length, channels: 88, rows: 3094, quoteReady: 0, finishedAt: new Date().toISOString() })
const output = resolve('backend/target/logistics-template-qa', dataset.id); await mkdir(output, { recursive: true })
await writeFile(resolve(output, 'report.json'), JSON.stringify(report, null, 2))
await writeFile(resolve(output, 'required-channels.json'), JSON.stringify(list, null, 2))
const md = ['# 日常必用渠道勾选清单', '', `核对范围：10家物流商、88个解析分组、3094条基础价格。极通多产品不等于仅两个实际下单产品。`, '', '以下均未预选，全部仍待完整计费验收；勾选只确定适配优先级，不授权生产切换。请优先在测试页面保存选择；本文件勾选不会自动同步数据库。', '', `[打开测试核对页面](${report.url})`, '']
for (const provider of [...new Set(list.channels.map(c => c.providerName))]) {
  md.push(`## ${provider}`, '')
  for (const c of list.channels.filter(c => c.providerName === provider)) {
    md.push(`- [ ] ${c.name} — ${c.priceRows} 条价格`, `  - 国家：${c.countries.join('、')}`, `  - 分区：${c.zones.join('；') || '无'}`, `  - 待适配：${c.pendingReasons.join('；') || '未提交完整版本计费验收'}`, '')
  }
}
await writeFile(resolve(output, '日常必用渠道勾选清单.md'), md.join('\n'))
const delivery = resolve('outputs/logistics-acceptance-20260831'); await mkdir(delivery, { recursive: true })
await writeFile(resolve(delivery, '日常必用渠道勾选清单.md'), md.join('\n'))
await writeFile(resolve(delivery, '模板验收报告.json'), JSON.stringify(report, null, 2))
console.log(JSON.stringify(report, null, 2)); console.log(`CHECKLIST=${resolve(output, '日常必用渠道勾选清单.md')}`)
