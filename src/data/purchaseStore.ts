import { api } from '@/services/http'

export type PurchasePriceTier = { minQty: number; maxQty: number | null; unitPriceCny: number; source: string }
export type PurchaseStockStatus = '有货' | '无货' | '待确认' | ''
export type PurchaseSkuOrigin = 'imported' | 'manual' | 'system'
export type PurchaseCatalogState = 'pending_template' | 'ready' | 'disabled'
export interface PurchaseDeletionCheck { canDelete:boolean;version:number;imageCount:number;supplierLinks:number;quotationRecords:number;drafts:number;templates:number;importBatches:number }

export type PurchaseProductRecord = {
  sourceRow: number; sku: string; skuOrigin: PurchaseSkuOrigin; category: string; _version?: number
  productImage: string; physicalImage: string; quotationOwner: string; quotationDate: string
  size: string; color: string; weightG: number | null; lengthCm: number | null; widthCm: number | null; heightCm: number | null
  minOrderQty: number | null; purchasePriceCny: number | null
  tier2MinQty: number | null; tier2PriceCny: number | null; tier3MinQty: number | null; tier3PriceCny: number | null
  priceTiers: PurchasePriceTier[]; singleFreightCny: number | null; freight10Cny: number | null; freight100Cny: number | null
  freeShipping: '' | '是' | '否'; taxIncludedPriceCny: number | null; invoiceType: string; stockStatus: PurchaseStockStatus
  notes: string; factoryInfo: string; sourceLink1: string; sourceLink2: string; sourceLink3: string; similarSource: string; auditNotes: string
  catalogState: PurchaseCatalogState; quoteReady: boolean; status: string; importWarnings: string[]
  // Compatibility aliases consumed by the existing quotation calculator.
  name: string; image: string; weightKg: number | null; colorSku: string; material: string; marks: string; shippingMarks: string[]
  rawTierPrice: string; l6Price: string; freightTrial: string; invoiceInfo: string; taxIncludedPrice: string; taxPoint: string
  taxDifference: string; packagingInfo: string; sourceLinks: string[]; otherNotes: string; more: string; weightDescription: string
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
  const catalogState: PurchaseCatalogState = input.catalogState === 'pending_template' || input.catalogState === 'disabled' ? input.catalogState : 'ready'
  const base = {
    sourceRow: Number(input.sourceRow) || Date.now(), sku, skuOrigin, category, productImage, _version: input._version == null ? undefined : Number(input._version),
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
  const reservedSku = /^(TESTP|TEST|DEMO|MOCK)/i.test(sku) || sku.startsWith('AUTO-')
  const quoteReady = catalogState === 'ready' && !reservedSku && skuOrigin !== 'system' && weightG != null && weightG > 0 && minOrderQty != null && minOrderQty > 0 && purchasePriceCny != null && purchasePriceCny >= 0
  const missing = [weightG == null || weightG <= 0 ? '重量' : '', minOrderQty == null || minOrderQty <= 0 ? '起订量' : '', purchasePriceCny == null ? '采购价' : ''].filter(Boolean)
  const status = catalogState === 'pending_template' ? '模板待补全（不可报价）' : catalogState === 'disabled' ? '已停用' : skuOrigin === 'system' ? '系统生成SKU，待修改' : quoteReady ? '资料完整' : `待补充${missing.length ? `：${missing.join('、')}` : ''}`
  const priceTiers = buildPriceTiers(base)
  return {
    ...base, catalogState, quoteReady, status, priceTiers,
    name: category || `商品 ${sku}`, image: productImage, weightKg: weightG == null ? null : weightG / 1000, colorSku: color,
    material: '', marks: '', shippingMarks: [], rawTierPrice: priceTiers.map(item => `${item.minQty}${item.maxQty == null ? '+' : `-${item.maxQty}`}件 ¥${item.unitPriceCny}`).join('；'),
    l6Price: '', freightTrial: '', invoiceInfo: '', taxIncludedPrice: base.taxIncludedPriceCny == null ? '' : String(base.taxIncludedPriceCny),
    taxPoint: '', taxDifference: base.invoiceType, packagingInfo: base.factoryInfo,
    sourceLinks: [base.sourceLink1, base.sourceLink2, base.sourceLink3, base.similarSource], otherNotes: '', more: '', weightDescription: weightG == null ? '' : String(weightG),
  }
}

export type PurchasePage = { items: PurchaseProductRecord[]; page: number; size: number; total: number; totalPages: number }
export type PurchaseStats = { total:number;ready:number;pending:number;generatedSku:number }
export async function loadPurchaseProductPage(query='',page=0,size=50):Promise<PurchasePage>{
  const result=await api.get<PurchasePage>(`/purchase-products?q=${encodeURIComponent(query)}&page=${page}&size=${size}`)
  return {...result,items:result.items.map(normalizePurchaseRecord)}
}
export const loadPurchaseStats=()=>api.get<PurchaseStats>('/purchase-products/stats')
export async function loadPurchaseProducts(query = '', page = 0, size = 500): Promise<PurchaseProductRecord[]> {
  const result = await loadPurchaseProductPage(query,page,size)
  return result.items
}

export async function loadPurchaseProduct(sku: string): Promise<PurchaseProductRecord> {
  return normalizePurchaseRecord(await api.get<PurchaseProductRecord>(`/purchase-products/${encodeURIComponent(sku)}`))
}

export async function savePurchaseProducts(records: PurchaseProductRecord[]) {
  await upsertPurchaseProducts(records)
}

export async function upsertPurchaseProducts(records: PurchaseProductRecord[]) {
  if (!records.length) return
  await api.put('/purchase-products/batch', records.map(normalizePurchaseRecord))
}

export async function loadPurchaseDeletionCheck(sku: string) {
  return api.get<PurchaseDeletionCheck>(`/purchase-products/${encodeURIComponent(sku)}/deletion-check`)
}

export async function setPurchaseProductCatalogState(sku: string, state: 'ready' | 'disabled', expectedVersion: number) {
  return normalizePurchaseRecord(await api.post<PurchaseProductRecord>(`/purchase-products/${encodeURIComponent(sku)}/catalog-state`, { state, expectedVersion }))
}

export async function deletePurchaseProduct(sku: string, expectedVersion: number) {
  await api.delete(`/purchase-products/${encodeURIComponent(sku)}?expectedVersion=${expectedVersion}`)
}

export async function promotePurchaseProduct(sourceSku: string, targetSku: string, expectedVersion: number) {
  return normalizePurchaseRecord(await api.post<PurchaseProductRecord>(`/purchase-products/${encodeURIComponent(sourceSku)}/promote`, { targetSku, expectedVersion }))
}

export async function resetPurchaseProducts() {
  const rows = await loadPurchaseProducts()
  await Promise.all(rows.map(row => deletePurchaseProduct(row.sku, row._version ?? -1)))
}

export function findPurchaseProduct(records: PurchaseProductRecord[], sku: string) {
  const normalized = sku.trim().toUpperCase().replace(/\s+/g, '')
  return records.find(item => item.sku === normalized && item.skuOrigin !== 'system' && item.quoteReady)
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
