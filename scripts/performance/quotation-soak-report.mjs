import fs from 'node:fs'
import path from 'node:path'
const root = 'artifacts/performance'
const names = ['soak-50-hot-read', 'soak-50-roles', 'soak-50-roles-capacity', 'soak-50-roles-index-tuned']
const phases = names.map(name => {
  const report = JSON.parse(fs.readFileSync(path.join(root, name + '.json'), 'utf8').replace(/^\uFEFF/, ''))
  const resources = JSON.parse(fs.readFileSync(path.join(root, name + '-resources.json'), 'utf8').replace(/^\uFEFF/, ''))
  const containers = {}
  for (const sample of resources) for (const container of sample.containers || []) {
    const key = container.Name
    const record = containers[key] ||= { cpuSamples: [], memorySamples: [] }
    record.cpuSamples.push(Number(container.CPUPerc.replace('%', '')))
    const memory = container.MemUsage.split('/')[0].trim()
    record.memorySamples.push(Number.parseFloat(memory) * (memory.includes('GiB') ? 1024 : memory.includes('KiB') ? 1 / 1024 : 1))
  }
  return { name, ...report, resources: {
    sampleCount: resources.length,
    failures: resources.filter(sample => sample.error).length,
    peakConnections: Math.max(...resources.map(sample => sample.db?.connections || 0)),
    peakLockWaits: Math.max(...resources.map(sample => sample.db?.lockWaits || 0)),
    containers: Object.fromEntries(Object.entries(containers).map(([key, values]) => [key, {
      cpuPeakPercent: Math.max(...values.cpuSamples), cpuMeanPercent: +(values.cpuSamples.reduce((a, b) => a + b, 0) / values.cpuSamples.length).toFixed(2),
      memoryPeakMiB: +Math.max(...values.memorySamples).toFixed(2), memoryFirstMiB: values.memorySamples[0], memoryLastMiB: values.memorySamples.at(-1),
    }])),
  } }
})
const abnormal = JSON.parse(fs.readFileSync(path.join(root, 'abnormal-50.json'), 'utf8'))
const browserBefore = JSON.parse(fs.readFileSync(path.join(root, 'browser-before-fix.json'), 'utf8'))
const browserAfter = JSON.parse(fs.readFileSync(path.join(root, 'browser-after-eligibility-fix.json'), 'utf8'))
const output = { generatedAt: new Date().toISOString(), phases, abnormal, browserBefore, browserAfter }
fs.mkdirSync('docs/performance', { recursive: true })
fs.writeFileSync('docs/performance/quotation-50-user-deep-results.json', JSON.stringify(output, null, 2) + '\n')
const table = ['| 场景 | 操作数 | 失败 | 物流 p95/p99 ms | 采购模糊搜索 p95 ms | 单品/组合保存 p95 ms |', '| --- | ---: | ---: | ---: | ---: | ---: |']
for (const p of phases) table.push(`| ${p.name} | ${p.total} | ${p.failures} | ${p.operations['logistics-query'].p95Ms}/${p.operations['logistics-query'].p99Ms} | ${p.operations['purchase-role-search']?.p95Ms ?? '—'} | ${p.operations['quotation-save-single']?.p95Ms ?? '—'}/${p.operations['quotation-save-bundle']?.p95Ms ?? '—'} |`)
process.stdout.write(table.join('\n') + '\n')
