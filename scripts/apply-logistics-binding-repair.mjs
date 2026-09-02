import { createHash, randomUUID } from 'node:crypto'
import { spawn } from 'node:child_process'
import { readFile, writeFile } from 'node:fs/promises'
import { basename, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const EXPECTED = { activeChannels: 88, quoteReadyChannels: 86, financeReferences: 226, financeUniqueChannels: 56, templates: 0 }

function sha256(buffer) { return createHash('sha256').update(buffer).digest('hex') }
function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`
  if (value && typeof value === 'object') return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`
  return JSON.stringify(value)
}
function dollarJson(tag, value) {
  const json = JSON.stringify(value)
  const delimiter = `$${tag}$`
  if (json.includes(delimiter)) throw new Error(`JSON contains reserved SQL delimiter ${delimiter}`)
  return `${delimiter}${json}${delimiter}`
}

export function buildRepairSql(before, after, summary, audit) {
  const beforeJson = dollarJson('before_policies', before)
  const afterJson = dollarJson('after_policies', after)
  const detailJson = dollarJson('audit_detail', audit.detail)
  return String.raw`
begin;
lock table finance_setting in row exclusive mode;
do $repair$
declare
  changed integer;
  active_channels integer;
  quote_ready_channels integer;
  finance_references integer;
  finance_unique_channels integer;
  template_count integer;
begin
  select count(*),count(*) filter(where coalesce(logistics_version_quote_ready(c.current_version_id),false))
    into active_channels,quote_ready_channels
    from logistics_channel c where c.dataset_id=logistics_active_dataset();
  select count(*),count(distinct allowed.value)
    into finance_references,finance_unique_channels
    from finance_setting f
    cross join lateral jsonb_array_elements(f.payload) policy
    cross join lateral jsonb_array_elements(coalesce(policy->'countryRules','[]'::jsonb)) country_rule
    cross join lateral jsonb_array_elements_text(coalesce(country_rule->'allowedChannels','[]'::jsonb)) allowed(value)
    where f.setting_key='channel-policies';
  select count(*) into template_count from quotation_template;
  if active_channels<>${EXPECTED.activeChannels} or quote_ready_channels<>${EXPECTED.quoteReadyChannels}
     or finance_references<>${EXPECTED.financeReferences} or finance_unique_channels<>${EXPECTED.financeUniqueChannels}
     or template_count<>${EXPECTED.templates} then
    raise exception 'production state changed: channels=% ready=% refs=% unique=% templates=%',active_channels,quote_ready_channels,finance_references,finance_unique_channels,template_count;
  end if;
  update finance_setting
     set payload=${afterJson}::jsonb,version=version+1,updated_at=now()
   where setting_key='channel-policies' and version=${Number(summary.financeVersion)} and payload=${beforeJson}::jsonb;
  get diagnostics changed=row_count;
  if changed<>1 then raise exception 'channel-policies version or payload changed'; end if;
  insert into audit_log(id,request_id,actor_account,action,resource_type,resource_id,outcome,detail,created_at)
  values('${audit.id}'::uuid,'${audit.requestId}','${audit.actor}','logistics.binding.repair','finance_setting','channel-policies','success',${detailJson}::jsonb,now());
end
$repair$;
commit;
select jsonb_build_object('version',version,'payload',payload)::text from finance_setting where setting_key='channel-policies';
`
}

function run(command, args, input) {
  return new Promise((resolveRun, reject) => {
    const child = spawn(command, args, { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] })
    const stdout = [], stderr = []
    child.stdout.on('data', chunk => stdout.push(chunk)); child.stderr.on('data', chunk => stderr.push(chunk))
    child.on('error', reject)
    child.on('close', code => code === 0 ? resolveRun(Buffer.concat(stdout)) : reject(new Error(`${command} exited ${code}: ${Buffer.concat(stderr).toString('utf8')}`)))
    child.stdin.end(input)
  })
}

function parseArgs(argv) {
  const values = {}, flags = new Set()
  for (let index = 0; index < argv.length; index++) {
    const key = argv[index]?.replace(/^--/, '')
    if (key === 'apply') { flags.add(key); continue }
    values[key] = argv[++index]
  }
  for (const key of ['archive', 'ssh-key', 'host']) if (!values[key]) throw new Error(`Missing --${key}`)
  if (flags.has('apply')) for (const key of ['authorized-by', 'expected-app-sha']) if (!values[key]) throw new Error(`--apply requires --${key}`)
  const actor = String(values['authorized-by'] || '')
  if (actor && !/^[A-Za-z0-9._-]{1,24}$/.test(actor)) throw new Error('authorized-by must be a production account identifier')
  const expectedAppSha = String(values['expected-app-sha'] || '').toLowerCase()
  if (expectedAppSha && !/^[0-9a-f]{40}$/.test(expectedAppSha)) throw new Error('expected-app-sha must be a full Git SHA')
  return { archive: resolve(values.archive), sshKey: resolve(values['ssh-key']), host: values.host, apply: flags.has('apply'), actor, expectedAppSha }
}

async function loadArchive(directory) {
  const sums = (await readFile(resolve(directory, 'SHA256SUMS'), 'utf8')).trim().split(/\r?\n/)
  for (const line of sums) {
    const match = line.match(/^([0-9a-f]{64}) {2}(.+)$/)
    if (!match || basename(match[2]) !== match[2]) throw new Error(`Invalid archive checksum row: ${line}`)
    const content = await readFile(resolve(directory, match[2]))
    if (sha256(content) !== match[1]) throw new Error(`Archive SHA-256 mismatch: ${match[2]}`)
  }
  const [summary, before, after] = await Promise.all(['mapping-summary.json', 'before-channel-policies.json', 'after-channel-policies.json'].map(async name => JSON.parse(await readFile(resolve(directory, name), 'utf8'))))
  for (const [key, expected] of Object.entries(EXPECTED)) if (summary.checks?.[key] !== expected) throw new Error(`Archive ${key} mismatch`)
  if (summary.beforePayloadSha256 !== sha256(Buffer.from(JSON.stringify(before)))) throw new Error('Archive before payload digest mismatch')
  if (summary.afterPayloadSha256 !== sha256(Buffer.from(JSON.stringify(after)))) throw new Error('Archive after payload digest mismatch')
  return { summary, before, after }
}

async function readProduction(options) {
  const ssh = ['-i', options.sshKey, '-o', 'BatchMode=yes', options.host]
  const query = String.raw`
with refs as (
  select count(*) reference_count,count(distinct allowed.value) unique_count
  from finance_setting f
  cross join lateral jsonb_array_elements(f.payload) policy
  cross join lateral jsonb_array_elements(coalesce(policy->'countryRules','[]'::jsonb)) country_rule
  cross join lateral jsonb_array_elements_text(coalesce(country_rule->'allowedChannels','[]'::jsonb)) allowed(value)
  where f.setting_key='channel-policies'
), channels as (
  select count(*) channel_count,count(*) filter(where coalesce(logistics_version_quote_ready(c.current_version_id),false)) ready_count
  from logistics_channel c where c.dataset_id=logistics_active_dataset()
)
select jsonb_build_object('finance',(select jsonb_build_object('version',version,'payload',payload) from finance_setting where setting_key='channel-policies'),
  'activeChannels',channels.channel_count,'quoteReadyChannels',channels.ready_count,'financeReferences',refs.reference_count,
  'financeUniqueChannels',refs.unique_count,'templates',(select count(*) from quotation_template))::text from refs,channels;
`
  const [database, manifest] = await Promise.all([
    run('ssh', [...ssh, 'docker exec -i quotation-prod-quotation-postgres-1 psql -X -qAt -v ON_ERROR_STOP=1 -U quotation_app -d quotation_prod'], Buffer.from(query)),
    run('ssh', [...ssh, "sed -n 's/^git_sha=//p' /srv/ahmln-data/quotation-app/current/manifest.txt"], Buffer.alloc(0)),
  ])
  return { ...JSON.parse(database.toString('utf8')), appSha: manifest.toString('utf8').trim() }
}

function assertCurrent(current, archive) {
  for (const [key, expected] of Object.entries(EXPECTED)) if (Number(current[key]) !== expected) throw new Error(`Production ${key} changed: expected ${expected}, actual ${current[key]}`)
  if (Number(current.finance?.version) !== Number(archive.summary.financeVersion)) throw new Error('Production finance version changed')
  if (canonicalJson(current.finance?.payload) !== canonicalJson(archive.before)) throw new Error('Production channel-policies payload changed')
}

async function main() {
  const options = parseArgs(process.argv.slice(2)), archive = await loadArchive(options.archive)
  const current = await readProduction(options)
  assertCurrent(current, archive)
  if (!options.apply) {
    process.stdout.write(`${JSON.stringify({ mode: 'dry-run', appSha: current.appSha, financeVersion: current.finance.version, checks: EXPECTED, beforePayloadSha256: archive.summary.beforePayloadSha256, afterPayloadSha256: archive.summary.afterPayloadSha256 }, null, 2)}\n`)
    return
  }
  if (current.appSha !== options.expectedAppSha) throw new Error(`Production SHA mismatch: expected ${options.expectedAppSha}, actual ${current.appSha}`)
  const audit = { id: randomUUID(), requestId: `logistics-binding-repair-${Date.now()}`, actor: options.actor, detail: {
    authorizedBy: options.actor, productionGitSha: current.appSha, backupSha256: archive.summary.backupSha256,
    beforePayloadSha256: archive.summary.beforePayloadSha256, afterPayloadSha256: archive.summary.afterPayloadSha256,
    verifiedBindings: archive.summary.classifications.verified, unavailableBindings: archive.summary.classifications.unavailable,
    restoredReferences: archive.summary.referencedClassifications.verified, unavailableReferences: archive.summary.referencedClassifications.unavailable,
  } }
  const ssh = ['-i', options.sshKey, '-o', 'BatchMode=yes', options.host]
  const output = await run('ssh', [...ssh, 'docker exec -i quotation-prod-quotation-postgres-1 psql -X -qAt -v ON_ERROR_STOP=1 -U quotation_app -d quotation_prod'], Buffer.from(buildRepairSql(archive.before, archive.after, archive.summary, audit)))
  const updated = JSON.parse(output.toString('utf8'))
  if (Number(updated.version) !== Number(archive.summary.financeVersion) + 1 || canonicalJson(updated.payload) !== canonicalJson(archive.after)) throw new Error('Transaction committed but readback verification failed')
  const receipt = { appliedAt: new Date().toISOString(), actor: options.actor, auditId: audit.id, requestId: audit.requestId, appSha: current.appSha, beforeVersion: archive.summary.financeVersion, afterVersion: updated.version, beforePayloadSha256: archive.summary.beforePayloadSha256, afterPayloadSha256: archive.summary.afterPayloadSha256 }
  await writeFile(resolve(options.archive, 'apply-receipt.json'), `${JSON.stringify(receipt, null, 2)}\n`, { encoding: 'utf8', flag: 'wx' })
  process.stdout.write(`${JSON.stringify(receipt, null, 2)}\n`)
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) main().catch(error => { console.error(error.message); process.exitCode = 1 })
