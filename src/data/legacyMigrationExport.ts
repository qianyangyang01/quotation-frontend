const EXCLUDED_KEY = /(password|passwd|secret|token|session|cookie|credential|auth|account|user)/i
const DRAFT_KEY = /(draft|temporary|temp|preview)/i
const TRAINING_KEY = /(?:^|[./_-])training(?:[./_-]|$)/i

type ExportEntry = { entryKey: string; source: 'localStorage' | 'indexedDB'; container: string; key: string; category: string; decision: 'migrate' | 'exclude' | 'review'; reason: string; count: number; value?: unknown }

function redact(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(redact)
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value as Record<string, unknown>).filter(([key]) => !EXCLUDED_KEY.test(key)).map(([key, item]) => [key, redact(item)]))
  return value
}
function parse(value: string) { try { return JSON.parse(value) as unknown } catch { return value } }
function size(value: unknown) { return Array.isArray(value) ? value.length : value && typeof value === 'object' ? Object.keys(value as object).length : value == null || value === '' ? 0 : 1 }
function category(name: string) {
  const key = name.toLowerCase()
  if (TRAINING_KEY.test(key)) return 'unknown'
  if (/purchase|product|采购|商品/.test(key)) return 'purchase'
  if (/logistic|freight|channel|物流|运费/.test(key)) return 'logistics'
  if (/finance|exchange|tax|country|grade|财务|汇率|税/.test(key)) return 'finance'
  if (/supplier|供应商/.test(key)) return 'supplier'
  if (/template|模板/.test(key)) return 'quotation-template'
  if (/quotation|record|history|报价|历史/.test(key)) return 'quotation-record'
  if (/customer|客户/.test(key)) return 'customer'
  return 'unknown'
}
export function legacyEntryDecision(name: string, value: unknown): Pick<ExportEntry, 'decision' | 'reason'> {
  if (TRAINING_KEY.test(name)) return { decision: 'exclude', reason: '培训系统数据禁止进入报价迁移' }
  if (EXCLUDED_KEY.test(name)) return { decision: 'exclude', reason: '账号、认证或敏感数据默认排除' }
  if (DRAFT_KEY.test(name)) return { decision: 'exclude', reason: '草稿、临时预览默认排除' }
  const kind = category(name)
  if (kind === 'supplier') return { decision: 'exclude', reason: '供应商主数据功能已下线' }
  if (kind === 'unknown') return { decision: 'review', reason: '无法自动确认业务归属' }
  const sample = typeof value === 'string' ? value.slice(0, 2000) : JSON.stringify(value).slice(0, 2000)
  if (/(?:^|["':\s])(TESTP\d*|AUTO-[A-Z0-9._/-]+|DEMO[A-Z0-9._/-]*|MOCK[A-Z0-9._/-]*)|演示|模拟|demo|mock|uat测试/i.test(sample)) return { decision: 'exclude', reason: '测试、模拟或系统生成数据永久排除' }
  return { decision: 'migrate', reason: '识别为报价业务候选数据，仍需人工白名单确认' }
}

async function indexedDbEntries(): Promise<ExportEntry[]> {
  const databases = typeof indexedDB.databases === 'function' ? await indexedDB.databases() : []
  const result: ExportEntry[] = []
  for (const metadata of databases) {
    if (!metadata.name) continue
    await new Promise<void>((resolve) => {
      const request = indexedDB.open(metadata.name!)
      request.onerror = () => resolve()
      request.onsuccess = () => {
        const database = request.result
        const stores = [...database.objectStoreNames]
        if (!stores.length) { database.close(); resolve(); return }
        const transaction = database.transaction(stores, 'readonly')
        for (const storeName of stores) {
          const getAll = transaction.objectStore(storeName).getAll()
          getAll.onsuccess = () => {
            const name = `${metadata.name}/${storeName}`; const safe = redact(getAll.result); const policy = legacyEntryDecision(name, safe)
            result.push({ entryKey: `indexedDB/${storeName}`, source: 'indexedDB', container: metadata.name!, key: storeName, category: category(name), ...policy, count: size(safe), ...(policy.decision === 'exclude' ? {} : { value: safe }) })
          }
        }
        transaction.oncomplete = () => { database.close(); resolve() }
        transaction.onerror = () => { database.close(); resolve() }
      }
    })
  }
  return result
}

async function imageHashes(entries: ExportEntry[]) {
  const hashes: Array<{ source: string; path: string; sha256: string; bytes: number }> = []
  async function walk(value: unknown, path: string) {
    if (typeof value === 'string' && value.startsWith('data:image/') && value.includes(',')) {
      const bytes = Uint8Array.from(atob(value.slice(value.indexOf(',') + 1)), char => char.charCodeAt(0))
      hashes.push({ source: 'browser-data-url', path, sha256: [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map(byte => byte.toString(16).padStart(2, '0')).join(''), bytes: bytes.length }); return
    }
    if (Array.isArray(value)) for (let index = 0; index < value.length; index++) await walk(value[index], `${path}[${index}]`)
    else if (value && typeof value === 'object') for (const [key, item] of Object.entries(value as object)) await walk(item, `${path}.${key}`)
  }
  for (const entry of entries) if (entry.value) await walk(entry.value, `${entry.container}.${entry.key}`)
  return hashes
}

export async function buildLegacyMigrationExport() {
  const entries: ExportEntry[] = []
  for (let index = 0; index < localStorage.length; index++) {
    const key = localStorage.key(index); if (!key) continue
    const raw = localStorage.getItem(key) || ''; const value = redact(parse(raw)); const policy = legacyEntryDecision(key, raw)
    entries.push({ entryKey: `localStorage/${key}`, source: 'localStorage', container: window.location.origin, key, category: category(key), ...policy, count: size(value), ...(policy.decision === 'exclude' ? {} : { value }) })
  }
  entries.push(...await indexedDbEntries())
  const images = await imageHashes(entries)
  const totals = { entries: entries.length, migrate: entries.filter(item => item.decision === 'migrate').length, exclude: entries.filter(item => item.decision === 'exclude').length, review: entries.filter(item => item.decision === 'review').length, images: images.length, imageBytes: images.reduce((sum, image) => sum + image.bytes, 0) }
  return { schemaVersion: 2, sourceType: 'legacy-browser-report', sourceOrigin: window.location.origin, exportedAt: new Date().toISOString(), policy: { passwordsSessionsAccountsExcluded: true, draftsExcluded: true, testDataExcluded: true, requiresManualWhitelistConfirmation: true }, staticInventory: { logisticsRules: 66, logisticsPriceRows: 3241, note: '仅作代码内置数据盘点提示，不自动进入迁移白名单' }, totals, entries, images, errors: [] }
}

export function downloadMigrationExport(report: Awaited<ReturnType<typeof buildLegacyMigrationExport>>) {
  const blob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' }); const link = document.createElement('a')
  link.href = URL.createObjectURL(blob); link.download = `quotation-migration-whitelist-${new Date().toISOString().slice(0, 10)}.json`; link.click(); URL.revokeObjectURL(link.href)
}
