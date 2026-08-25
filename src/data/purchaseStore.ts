import { quotationProductCategories } from './productCategories'

export type PurchasePriceTier = { minQty: number; maxQty: number | null; unitPriceCny: number; source: string }
export type PurchaseStockStatus = '有货' | '无货' | '待确认' | ''
export type PurchaseSkuOrigin = 'imported' | 'manual' | 'system'

export type PurchaseProductRecord = {
  sourceRow: number; sku: string; skuOrigin: PurchaseSkuOrigin; category: string
  productImage: string; physicalImage: string; quotationOwner: string; quotationDate: string
  size: string; color: string; weightG: number | null; lengthCm: number | null; widthCm: number | null; heightCm: number | null
  minOrderQty: number | null; purchasePriceCny: number | null
  tier2MinQty: number | null; tier2PriceCny: number | null; tier3MinQty: number | null; tier3PriceCny: number | null
  priceTiers: PurchasePriceTier[]; singleFreightCny: number | null; freight10Cny: number | null; freight100Cny: number | null
  freeShipping: '' | '是' | '否'; taxIncludedPriceCny: number | null; invoiceType: string; stockStatus: PurchaseStockStatus
  notes: string; factoryInfo: string; sourceLink1: string; sourceLink2: string; sourceLink3: string; similarSource: string; auditNotes: string
  quoteReady: boolean; status: string; importWarnings: string[]
  // Compatibility aliases consumed by the existing quotation calculator.
  name: string; image: string; weightKg: number | null; colorSku: string; material: string; marks: string; shippingMarks: string[]
  rawTierPrice: string; l6Price: string; freightTrial: string; invoiceInfo: string; taxIncludedPrice: string; taxPoint: string
  taxDifference: string; packagingInfo: string; sourceLinks: string[]; otherNotes: string; more: string; weightDescription: string
}

const DB_NAME = 'milano-quotation'
const DB_VERSION = 1
const STORE_NAME = 'purchase-products'
const LEGACY_STORAGE_KEY = 'milano.purchase-products.v1'
const CATEGORY_MIGRATION_KEY = 'milano.purchase-category-migration.v1'
let databasePromise: Promise<IDBDatabase> | null = null

function openDatabase() {
  if (typeof indexedDB === 'undefined') return Promise.reject(new Error('当前浏览器不支持 IndexedDB'))
  if (databasePromise) return databasePromise
  databasePromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) request.result.createObjectStore(STORE_NAME, { keyPath: 'sku' })
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error || new Error('采购数据库打开失败'))
  })
  return databasePromise
}

function requestResult<T>(request: IDBRequest<T>) {
  return new Promise<T>((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error || new Error('采购数据库操作失败'))
  })
}

function transactionDone(transaction: IDBTransaction) {
  return new Promise<void>((resolve, reject) => {
    transaction.oncomplete = () => resolve()
    transaction.onerror = () => reject(transaction.error || new Error('采购数据库保存失败'))
    transaction.onabort = () => reject(transaction.error || new Error('采购数据库保存已取消'))
  })
}

function stableCategory(sku: string, sourceRow: number) {
  const seed = `${sku}:${sourceRow}`
  let hash = 2166136261
  for (let index = 0; index < seed.length; index += 1) hash = Math.imul(hash ^ seed.charCodeAt(index), 16777619)
  return quotationProductCategories[(hash >>> 0) % quotationProductCategories.length]
}

async function migrateExistingPurchaseCategories(database: IDBDatabase) {
  if (typeof window === 'undefined' || window.localStorage.getItem(CATEGORY_MIGRATION_KEY) === '1') return
  const rows = await requestResult(database.transaction(STORE_NAME, 'readonly').objectStore(STORE_NAME).getAll()) as PurchaseProductRecord[]
  if (rows.length) {
    const transaction = database.transaction(STORE_NAME, 'readwrite')
    const store = transaction.objectStore(STORE_NAME)
    rows.forEach(record => store.put({ ...record, category: stableCategory(record.sku, record.sourceRow) }))
    await transactionDone(transaction)
  }
  window.localStorage.setItem(CATEGORY_MIGRATION_KEY, '1')
}

function numberOrNull(value: unknown) {
  if (value === '' || value == null) return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

function buildPriceTiers(record: Pick<PurchaseProductRecord, 'minOrderQty' | 'purchasePriceCny' | 'tier2MinQty' | 'tier2PriceCny' | 'tier3MinQty' | 'tier3PriceCny'>) {
  const candidates = [
    { minQty: record.minOrderQty, price: record.purchasePriceCny, source: '基准采购单价' },
    { minQty: record.tier2MinQty, price: record.tier2PriceCny, source: '阶梯价2' },
    { minQty: record.tier3MinQty, price: record.tier3PriceCny, source: '阶梯价3' },
  ].filter((item): item is { minQty: number; price: number; source: string } => item.minQty != null && item.minQty > 0 && item.price != null && item.price >= 0)
    .sort((a, b) => a.minQty - b.minQty)
  return candidates.map((item, index) => ({
    minQty: Math.max(1, Math.floor(item.minQty)),
    maxQty: candidates[index + 1] ? Math.max(1, Math.floor(candidates[index + 1].minQty)) - 1 : null,
    unitPriceCny: item.price,
    source: item.source,
  }))
}

export function normalizePurchaseRecord(input: Partial<PurchaseProductRecord>): PurchaseProductRecord {
  const sku = String(input.sku || '').trim().toUpperCase().replace(/\s+/g, '')
  const skuOrigin: PurchaseSkuOrigin = input.skuOrigin === 'system' || sku.startsWith('AUTO-') ? 'system' : input.skuOrigin === 'manual' ? 'manual' : 'imported'
  const weightG = numberOrNull(input.weightG)
  const minOrderQty = numberOrNull(input.minOrderQty)
  const purchasePriceCny = numberOrNull(input.purchasePriceCny)
  const category = String(input.category || input.name || '').trim()
  const productImage = String(input.productImage || input.image || '')
  const color = String(input.color || input.colorSku || '').trim()
  const sourceLinks = input.sourceLinks || []
  const base = {
    sourceRow: Number(input.sourceRow) || Date.now(), sku, skuOrigin, category, productImage,
    physicalImage: String(input.physicalImage || ''), quotationOwner: String(input.quotationOwner || '').trim(), quotationDate: String(input.quotationDate || ''),
    size: String(input.size || '').trim(), color, weightG, lengthCm: numberOrNull(input.lengthCm), widthCm: numberOrNull(input.widthCm), heightCm: numberOrNull(input.heightCm),
    minOrderQty, purchasePriceCny, tier2MinQty: numberOrNull(input.tier2MinQty), tier2PriceCny: numberOrNull(input.tier2PriceCny),
    tier3MinQty: numberOrNull(input.tier3MinQty), tier3PriceCny: numberOrNull(input.tier3PriceCny),
    singleFreightCny: numberOrNull(input.singleFreightCny), freight10Cny: numberOrNull(input.freight10Cny), freight100Cny: numberOrNull(input.freight100Cny),
    freeShipping: input.freeShipping === '是' || input.freeShipping === '否' ? input.freeShipping : '' as '' | '是' | '否',
    taxIncludedPriceCny: numberOrNull(input.taxIncludedPriceCny ?? input.taxIncludedPrice), invoiceType: String(input.invoiceType || input.taxDifference || '').trim(),
    stockStatus: input.stockStatus === '有货' || input.stockStatus === '无货' || input.stockStatus === '待确认' ? input.stockStatus : '' as PurchaseStockStatus,
    notes: String(input.notes || '').trim(), factoryInfo: String(input.factoryInfo || input.packagingInfo || '').trim(),
    sourceLink1: String(input.sourceLink1 || sourceLinks[0] || '').trim(), sourceLink2: String(input.sourceLink2 || sourceLinks[1] || '').trim(),
    sourceLink3: String(input.sourceLink3 || sourceLinks[2] || '').trim(), similarSource: String(input.similarSource || sourceLinks[3] || '').trim(),
    auditNotes: String(input.auditNotes || '').trim(), importWarnings: Array.isArray(input.importWarnings) ? [...input.importWarnings] : [],
  }
  const quoteReady = skuOrigin !== 'system' && weightG != null && weightG > 0 && minOrderQty != null && minOrderQty > 0 && purchasePriceCny != null && purchasePriceCny >= 0
  const missing = [weightG == null || weightG <= 0 ? '重量' : '', minOrderQty == null || minOrderQty <= 0 ? '起订量' : '', purchasePriceCny == null ? '采购价' : ''].filter(Boolean)
  const status = skuOrigin === 'system' ? '系统生成SKU，待修改' : quoteReady ? '资料完整' : `待补充${missing.length ? `：${missing.join('、')}` : ''}`
  const priceTiers = buildPriceTiers(base)
  return {
    ...base, quoteReady, status, priceTiers,
    name: category || `商品 ${sku}`, image: productImage, weightKg: weightG == null ? null : weightG / 1000, colorSku: color,
    material: '', marks: '', shippingMarks: [], rawTierPrice: priceTiers.map(item => `${item.minQty}${item.maxQty == null ? '+' : `-${item.maxQty}`}件 ¥${item.unitPriceCny}`).join('；'),
    l6Price: '', freightTrial: '', invoiceInfo: '', taxIncludedPrice: base.taxIncludedPriceCny == null ? '' : String(base.taxIncludedPriceCny),
    taxPoint: '', taxDifference: base.invoiceType, packagingInfo: base.factoryInfo,
    sourceLinks: [base.sourceLink1, base.sourceLink2, base.sourceLink3, base.similarSource], otherNotes: '', more: '', weightDescription: weightG == null ? '' : String(weightG),
  }
}

export async function loadPurchaseProducts(): Promise<PurchaseProductRecord[]> {
  if (typeof window !== 'undefined') window.localStorage.removeItem(LEGACY_STORAGE_KEY)
  const database = await openDatabase()
  await migrateExistingPurchaseCategories(database)
  const rows = await requestResult(database.transaction(STORE_NAME, 'readonly').objectStore(STORE_NAME).getAll())
  return (rows as PurchaseProductRecord[]).map(normalizePurchaseRecord).sort((a, b) => b.sourceRow - a.sourceRow)
}

export async function savePurchaseProducts(records: PurchaseProductRecord[]) {
  const database = await openDatabase()
  const transaction = database.transaction(STORE_NAME, 'readwrite')
  const store = transaction.objectStore(STORE_NAME)
  store.clear()
  records.map(normalizePurchaseRecord).forEach(record => store.put(record))
  await transactionDone(transaction)
}

export async function upsertPurchaseProducts(records: PurchaseProductRecord[]) {
  const database = await openDatabase()
  const transaction = database.transaction(STORE_NAME, 'readwrite')
  const store = transaction.objectStore(STORE_NAME)
  records.map(normalizePurchaseRecord).forEach(record => store.put(record))
  await transactionDone(transaction)
}

export async function deletePurchaseProduct(sku: string) {
  const database = await openDatabase()
  const transaction = database.transaction(STORE_NAME, 'readwrite')
  transaction.objectStore(STORE_NAME).delete(sku)
  await transactionDone(transaction)
}

export async function resetPurchaseProducts() {
  if (typeof window !== 'undefined') window.localStorage.removeItem(LEGACY_STORAGE_KEY)
  const database = await openDatabase()
  const transaction = database.transaction(STORE_NAME, 'readwrite')
  transaction.objectStore(STORE_NAME).clear()
  await transactionDone(transaction)
}

export function findPurchaseProduct(records: PurchaseProductRecord[], sku: string) {
  const normalized = sku.trim().toUpperCase().replace(/\s+/g, '')
  return records.find(item => item.sku === normalized && item.skuOrigin !== 'system')
}

export function purchaseUnitPrice(record: PurchaseProductRecord, quantity: number) {
  const qty = Math.max(1, Math.floor(quantity || 1))
  const tier = record.priceTiers.find(item => qty >= item.minQty && (item.maxQty == null || qty <= item.maxQty)) || [...record.priceTiers].reverse().find(item => qty >= item.minQty)
  return tier?.unitPriceCny ?? record.purchasePriceCny ?? 0
}

export function purchaseFreightChoices(record: PurchaseProductRecord) {
  if (record.freeShipping === '是') return [1, 10, 100].map(quantity => ({ quantity, totalFreightCny: 0, unitFreightCny: 0 }))
  return [{ quantity: 1, totalFreightCny: record.singleFreightCny }, { quantity: 10, totalFreightCny: record.freight10Cny }, { quantity: 100, totalFreightCny: record.freight100Cny }]
    .filter((item): item is { quantity: number; totalFreightCny: number } => item.totalFreightCny != null)
    .map(item => ({ ...item, unitFreightCny: item.totalFreightCny / item.quantity }))
}

export function purchaseFreightUnit(record: PurchaseProductRecord, batchQuantity: number) {
  return purchaseFreightChoices(record).find(item => item.quantity === batchQuantity)?.unitFreightCny ?? 0
}
export function purchaseDisplayName(record: PurchaseProductRecord) { return record.category || `商品 ${record.sku}` }
export function formatPurchaseTiers(record: PurchaseProductRecord) {
  if (!record.priceTiers.length) return '暂无数据'
  return record.priceTiers.map(item => `${item.maxQty == null ? `${item.minQty}+件` : `${item.minQty}–${item.maxQty}件`} ¥${item.unitPriceCny.toFixed(2)}`).join('｜')
}
export const purchaseImportMeta = { sourceFile: '米莱诺采购产品标准导入模板-新版.xlsx', sourceSheet: '采购产品导入', importedAt: '', total: 0 }
