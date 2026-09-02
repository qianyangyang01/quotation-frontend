import { createHash } from 'node:crypto'
import { spawn } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const DEFAULT_EXPECTATIONS = { oldChannels: 67, activeChannels: 88, quoteReadyChannels: 86, financeReferences: 226, financeUniqueChannels: 56, templates: 0 }
const PROVIDER_ALIASES = new Map([['联邮通', '递四方']])

function text(value) { return String(value ?? '').trim() }
function unique(values) { return [...new Set(values.filter(Boolean))] }
function normalizedProvider(value) { const provider = text(value); return PROVIDER_ALIASES.get(provider) || provider }
function normalizedAttribute(value) {
  const attribute = text(value) || '普货'
  if (['特货', '带电带磁'].includes(attribute)) return '带电'
  if (['敏货', '特敏', '特敏货', '微敏感'].includes(attribute)) return '敏感货'
  if (['化妆品', '彩妆'].includes(attribute)) return '非液体化妆品'
  return attribute
}

export function canonicalChannelName(value, provider, attribute) {
  let name = text(value).toLocaleLowerCase('zh-CN')
  const providers = unique([text(provider), normalizedProvider(provider), '联邮通', '联邮'])
  for (const prefix of providers.sort((a, b) => b.length - a.length)) {
    if (prefix && name.startsWith(prefix.toLocaleLowerCase('zh-CN'))) name = name.slice(prefix.length)
  }
  name = name.replace(/[（(]([a-z0-9-]{1,12})[）)]/gi, '')
  name = name.replace(/[【】[\]（）()\s_/-]/g, '')
  name = name.replace(/专线/g, '')
  const normalized = normalizedAttribute(attribute)
  const removable = normalized === '普货' ? ['普货']
    : normalized === '带电' ? ['带电', '特货']
      : normalized === '敏感货' ? ['敏感货', '敏货', '特敏货', '特敏']
        : normalized === '非液体化妆品' ? ['非液体化妆品', '化妆品', '彩妆'] : []
  for (const token of removable) name = name.replaceAll(token, '')
  return name
}

function sha256(buffer) { return createHash('sha256').update(buffer).digest('hex') }
function channelKey(channel) { return `${channel.ruleId}::${channel.providerName}::${channel.code}` }
function backupVersionRows(snapshot, channel) {
  const version = (snapshot.fullVersions || []).find(item => text(item.id) === text(channel.currentVersionId))
  return Array.isArray(version?.payload?.rows) ? version.payload.rows : []
}
function countryEvidence(rows) {
  return {
    codes: unique(rows.map(row => text(row.countryCode))).sort(),
    names: unique(rows.map(row => text(row.areaName))).sort((a, b) => a.localeCompare(b, 'zh-CN')),
  }
}
function currentChannelRecord(channel) {
  return {
    id: text(channel.id), providerName: text(channel.providerName), name: text(channel.name), code: text(channel.code),
    ruleId: Number(channel.ruleId), channelKey: text(channel.channelKey) || channelKey(channel),
    logisticsAttribute: normalizedAttribute(channel.logisticsAttribute), quoteReady: channel.quoteReady === true,
    countries: unique(channel.countries || []).sort(), areaNames: unique(channel.areaNames || []).sort((a, b) => a.localeCompare(b, 'zh-CN')),
    priceRows: Number(channel.priceRows || 0),
  }
}

function financeReferences(payload) {
  const refs = []
  for (const policy of Array.isArray(payload) ? payload : []) {
    for (const rule of Array.isArray(policy.countryRules) ? policy.countryRules : []) {
      for (const key of Array.isArray(rule.allowedChannels) ? rule.allowedChannels : []) {
        refs.push({ key: text(key), category: normalizedAttribute(policy.category), country: text(rule.country) })
      }
    }
  }
  return refs
}

export function classifyBindings(snapshot, current, backupSha) {
  const refs = financeReferences(current.finance.payload)
  const referenceIndex = new Map()
  for (const ref of refs) {
    const list = referenceIndex.get(ref.key) || []
    list.push(ref); referenceIndex.set(ref.key, list)
  }
  const active = (current.channels || []).map(currentChannelRecord)
  const old = (snapshot.channels || []).map(channel => {
    const rows = backupVersionRows(snapshot, channel)
    const countries = countryEvidence(rows)
    return {
      id: text(channel.id), providerName: text(channel.providerName), name: text(channel.name), code: text(channel.code),
      ruleId: Number(channel.ruleId), channelKey: text(channel.channelKey) || channelKey(channel),
      logisticsAttribute: normalizedAttribute(channel.logisticsAttribute), countries: countries.codes, areaNames: countries.names,
      priceRows: rows.length, referencedBy: referenceIndex.get(text(channel.channelKey) || channelKey(channel)) || [],
    }
  })

  const preliminary = old.map(source => {
    const provider = normalizedProvider(source.providerName)
    const canonical = canonicalChannelName(source.name, source.providerName, source.logisticsAttribute)
    const requiredCountries = unique(source.referencedBy.map(ref => ref.country))
    const candidates = active.filter(target => target.quoteReady
      && normalizedProvider(target.providerName) === provider
      && target.logisticsAttribute === source.logisticsAttribute
      && canonicalChannelName(target.name, target.providerName, target.logisticsAttribute) === canonical
      && requiredCountries.every(country => target.areaNames.includes(country) || target.countries.includes(country)))
    const status = candidates.length === 1 ? 'verified' : candidates.length > 1 ? 'ambiguous' : 'unavailable'
    return { ...source, canonicalName: canonical, status, candidates, target: candidates.length === 1 ? candidates[0] : null, backupSha256: backupSha }
  })

  const targetUsage = new Map()
  for (const row of preliminary.filter(row => row.status === 'verified')) {
    const used = targetUsage.get(row.target.channelKey) || []
    used.push(row); targetUsage.set(row.target.channelKey, used)
  }
  return preliminary.map(row => {
    const collision = row.target && (targetUsage.get(row.target.channelKey) || []).length > 1
    if (!collision) return row
    return { ...row, status: 'ambiguous', reason: 'multiple-old-channels-target-one-current-channel', target: null }
  })
}

function unavailableEntry(binding) {
  return {
    legacyKey: binding.channelKey, providerName: binding.providerName, channelName: binding.name,
    status: binding.status, reason: binding.reason || (binding.status === 'ambiguous' ? 'multiple-current-candidates' : 'no-current-equivalent'),
    backupSha256: binding.backupSha256,
  }
}

export function buildFinancePreview(payload, bindings) {
  const byKey = new Map(bindings.map(binding => [binding.channelKey, binding]))
  return (Array.isArray(payload) ? payload : []).map(policy => ({
    ...policy,
    countryRules: (Array.isArray(policy.countryRules) ? policy.countryRules : []).map(rule => {
      const allowedChannels = []
      const unavailableChannels = [...(Array.isArray(rule.unavailableChannels) ? rule.unavailableChannels : [])]
      for (const key of Array.isArray(rule.allowedChannels) ? rule.allowedChannels : []) {
        const binding = byKey.get(text(key))
        if (!binding) { allowedChannels.push(text(key)); continue }
        if (binding.status === 'verified' && binding.target
          && (binding.target.areaNames.includes(text(rule.country)) || binding.target.countries.includes(text(rule.country)))) {
          allowedChannels.push(binding.target.channelKey)
        } else unavailableChannels.push(unavailableEntry(binding))
      }
      return {
        ...rule,
        allowedChannels: unique(allowedChannels),
        unavailableChannels: [...new Map(unavailableChannels.map(item => [item.legacyKey, item])).values()],
      }
    }),
  }))
}

function assertExpectations(snapshot, current, bindings, expectations = DEFAULT_EXPECTATIONS) {
  const refs = financeReferences(current.finance.payload)
  const checks = {
    oldChannels: (snapshot.channels || []).length,
    activeChannels: (current.channels || []).length,
    quoteReadyChannels: (current.channels || []).filter(channel => channel.quoteReady === true).length,
    financeReferences: refs.length,
    financeUniqueChannels: new Set(refs.map(ref => ref.key)).size,
    templates: Number(current.templates || 0),
  }
  for (const [key, expected] of Object.entries(expectations)) {
    if (checks[key] !== expected) throw new Error(`${key} changed: expected ${expected}, actual ${checks[key]}`)
  }
  if (bindings.length !== expectations.oldChannels) throw new Error('Not every old channel was classified')
  return checks
}

function csvCell(value) { const raw = Array.isArray(value) ? value.join(',') : text(value); return `"${raw.replaceAll('"', '""')}"` }
function mappingCsv(bindings) {
  const header = ['status', 'referencedOccurrences', 'oldChannelKey', 'oldProvider', 'oldChannel', 'attribute', 'oldCountries', 'oldPriceRows', 'newChannelKey', 'newProvider', 'newChannel', 'newCountries', 'candidateCount', 'reason']
  const rows = bindings.map(row => [row.status, row.referencedBy.length, row.channelKey, row.providerName, row.name, row.logisticsAttribute, row.countries, row.priceRows,
    row.target?.channelKey, row.target?.providerName, row.target?.name, row.target?.countries || [], row.candidates.length, row.reason || ''])
  return [header, ...rows].map(row => row.map(csvCell).join(',')).join('\r\n') + '\r\n'
}

function run(command, args, input) {
  return new Promise((resolveRun, reject) => {
    const child = spawn(command, args, { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] })
    const stdout = [], stderr = []
    child.stdout.on('data', chunk => stdout.push(chunk)); child.stderr.on('data', chunk => stderr.push(chunk))
    child.on('error', reject)
    child.on('close', code => code === 0 ? resolveRun(Buffer.concat(stdout)) : reject(new Error(`${command} exited ${code}: ${Buffer.concat(stderr).toString('utf8')}`)))
    if (input) child.stdin.end(input); else child.stdin.end()
  })
}

async function readProduction(options) {
  const ssh = ['-i', options.sshKey, '-o', 'BatchMode=yes', options.host]
  const object = options.backupObject.replaceAll('"', '')
  const backupCommand = `docker exec quotation-prod-quotation-minio-1 sh -lc 'MC_HOST_local="http://\${MINIO_ROOT_USER}:\${MINIO_ROOT_PASSWORD}@127.0.0.1:9000" exec mc cat "local/quotation-assets/${object}"'`
  const backup = await run('ssh', [...ssh, backupCommand])
  const query = String.raw`
with channel_rows as (
  select c.id,c.rule_id,c.code,c.current_version_id,c.payload,p.payload->>'name' provider_name,
         coalesce(logistics_version_quote_ready(c.current_version_id),false) quote_ready,
         coalesce(array_agg(distinct item->>'countryCode') filter(where item->>'countryCode'<>''),'{}') countries,
         coalesce(array_agg(distinct item->>'areaName') filter(where item->>'areaName'<>''),'{}') area_names,
         count(item)::int price_rows
  from logistics_channel c join logistics_provider p on p.id=c.provider_id
  left join logistics_version v on v.id=c.current_version_id
  left join lateral jsonb_array_elements(coalesce(v.payload->'rows','[]'::jsonb)) item on true
  where c.dataset_id=logistics_active_dataset()
  group by c.id,c.rule_id,c.code,c.current_version_id,c.payload,p.payload
), current_state as (
  select jsonb_build_object(
    'channels',coalesce((select jsonb_agg(payload || jsonb_build_object('id',id,'ruleId',rule_id,'code',code,'providerName',provider_name,'channelKey',concat(rule_id,'::',provider_name,'::',code),'quoteReady',quote_ready,'countries',countries,'areaNames',area_names,'priceRows',price_rows) order by provider_name,payload->>'name') from channel_rows),'[]'::jsonb),
    'finance',(select jsonb_build_object('version',version,'updatedAt',updated_at,'payload',payload) from finance_setting where setting_key='channel-policies'),
    'templates',(select count(*) from quotation_template),
    'activation',(select payload->'activation' from logistics_dataset where status='active')
  ) value
)
select value::text from current_state;
`
  const currentBuffer = await run('ssh', [...ssh, "docker exec -i quotation-prod-quotation-postgres-1 psql -X -v ON_ERROR_STOP=1 -U quotation_app -d quotation_prod -At"], Buffer.from(query))
  return { backup, current: JSON.parse(currentBuffer.toString('utf8')) }
}

async function writeReports(outDir, snapshot, current, bindings, beforeSha) {
  await mkdir(outDir, { recursive: true })
  const after = buildFinancePreview(current.finance.payload, bindings)
  const summary = {
    generatedAt: new Date().toISOString(), backupSha256: beforeSha,
    checks: assertExpectations(snapshot, current, bindings), financeVersion: current.finance.version,
    classifications: Object.fromEntries(['verified', 'ambiguous', 'unavailable'].map(status => [status, bindings.filter(row => row.status === status).length])),
    referencedClassifications: Object.fromEntries(['verified', 'ambiguous', 'unavailable'].map(status => [status, bindings.filter(row => row.status === status).reduce((sum, row) => sum + row.referencedBy.length, 0)])),
    beforePayloadSha256: sha256(Buffer.from(JSON.stringify(current.finance.payload))),
    afterPayloadSha256: sha256(Buffer.from(JSON.stringify(after))),
  }
  const files = {
    'mapping-summary.json': JSON.stringify(summary, null, 2) + '\n',
    'mapping.json': JSON.stringify(bindings, null, 2) + '\n',
    'mapping.csv': mappingCsv(bindings),
    'before-channel-policies.json': JSON.stringify(current.finance.payload, null, 2) + '\n',
    'after-channel-policies.json': JSON.stringify(after, null, 2) + '\n',
  }
  const sums = []
  for (const [name, content] of Object.entries(files)) {
    await writeFile(resolve(outDir, name), content, { encoding: 'utf8', flag: 'wx' })
    sums.push(`${sha256(Buffer.from(content))}  ${name}`)
  }
  await writeFile(resolve(outDir, 'SHA256SUMS'), sums.join('\n') + '\n', { encoding: 'utf8', flag: 'wx' })
  return summary
}

function parseArgs(argv) {
  const values = {}
  for (let index = 0; index < argv.length; index += 2) values[argv[index]?.replace(/^--/, '')] = argv[index + 1]
  for (const key of ['ssh-key', 'host', 'backup-object', 'expected-sha', 'out-dir']) if (!values[key]) throw new Error(`Missing --${key}`)
  return { sshKey: resolve(values['ssh-key']), host: values.host, backupObject: values['backup-object'], expectedSha: values['expected-sha'].toLowerCase(), outDir: resolve(values['out-dir']) }
}

async function main() {
  const options = parseArgs(process.argv.slice(2))
  const { backup, current } = await readProduction(options)
  const actualSha = sha256(backup)
  if (actualSha !== options.expectedSha) throw new Error(`Backup SHA-256 mismatch: expected ${options.expectedSha}, actual ${actualSha}`)
  const snapshot = JSON.parse(backup.toString('utf8'))
  const bindings = classifyBindings(snapshot, current, actualSha)
  const summary = await writeReports(options.outDir, snapshot, current, bindings, actualSha)
  process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`)
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) main().catch(error => { console.error(error.message); process.exitCode = 1 })
