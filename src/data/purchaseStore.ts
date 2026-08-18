import importedPurchaseData from './purchaseProducts.json'

export type PurchasePriceTier = {
  minQty: number
  maxQty: number | null
  unitPriceCny: number
  source: string
}

export type PurchaseStockStatus = '有货' | '无货' | '待确认'

export type PurchaseProductRecord = {
  sourceRow: number
  sku: string
  name: string
  image: string
  stockStatus: PurchaseStockStatus
  quotationOwner: string
  quotationDate: string
  notes: string
  weightDescription: string
  weightG: number | null
  weightKg: number | null
  size: string
  colorSku: string
  material: string
  marks: string
  shippingMarks: string[]
  minOrderQty: number
  purchasePriceCny: number | null
  priceTiers: PurchasePriceTier[]
  rawTierPrice: string
  l6Price: string
  freightTrial: string
  singleFreightCny: number | null
  freight10Cny: number | null
  freight100Cny: number | null
  category: string
  invoiceInfo: string
  taxIncludedPrice: string
  taxPoint: string
  taxDifference: string
  packagingInfo: string
  invoiceType: string
  factoryInfo: string
  auditNotes: string
  sourceLinks: string[]
  otherNotes: string
  more: string
  status: string
}

const STORAGE_KEY = 'milano.purchase-products.v1'
const importedRecords = importedPurchaseData.products as PurchaseProductRecord[]

function normalizeStockStatus(value: unknown, notes = '', more = ''): PurchaseStockStatus {
  if (value === '有货' || value === '无货' || value === '待确认') return value
  const sourceText = `${notes} ${more}`
  if (/无现货|暂无现货|当前无货|已经缺货|现已缺货|断货|售罄/.test(sourceText)) return '无货'
  if (/现货数量不足|库存不足|下单前确认|暂时现货|缺货补货周期|库存待确认/.test(sourceText)) return '待确认'
  if (/有现货|现货充足|库存充足|现货/.test(sourceText)) return '有货'
  return '待确认'
}

function freightTotalFromText(text: string, quantity: number) {
  const match = text.match(new RegExp(`${quantity}\\s*件\\s*(?:运费)?\\s*[:：]?\\s*[¥￥]?\\s*(\\d+(?:\\.\\d+)?)`))
  return match ? Number(match[1]) : null
}

function normalizeRecord(item: PurchaseProductRecord): PurchaseProductRecord {
  const source = importedRecords.find(record => record.sourceRow === item.sourceRow && record.sku === item.sku)
  // Keep legacy import markers intact for historical compatibility. They are no
  // longer assigned or consumed by the purchase-maintenance workflow.
  const storedMarks = item.shippingMarks?.length
    ? item.shippingMarks
    : source?.shippingMarks?.length
      ? source.shippingMarks
      : (item.marks || '').split(/[、,，]/).filter(Boolean)
  return {
    ...item,
    stockStatus: normalizeStockStatus(item.stockStatus ?? source?.stockStatus, item.notes, item.more),
    shippingMarks: [...storedMarks],
    invoiceType: item.invoiceType ?? source?.invoiceType ?? item.taxDifference ?? '',
    factoryInfo: item.factoryInfo ?? source?.factoryInfo ?? item.packagingInfo ?? '',
    singleFreightCny: item.singleFreightCny ?? freightTotalFromText(item.freightTrial || '', 1),
    freight10Cny: item.freight10Cny ?? source?.freight10Cny ?? freightTotalFromText(item.freightTrial || '', 10),
    freight100Cny: item.freight100Cny ?? source?.freight100Cny ?? freightTotalFromText(item.freightTrial || '', 100),
    sourceLinks: item.sourceLinks?.length === 4 ? item.sourceLinks : source?.sourceLinks ?? item.sourceLinks ?? [],
    more: item.more ?? source?.more ?? '',
  }
}

export function loadPurchaseProducts(): PurchaseProductRecord[] {
  if (typeof window === 'undefined') return importedRecords.map(normalizeRecord)
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY)
    if (saved) return (JSON.parse(saved) as PurchaseProductRecord[]).map(normalizeRecord)
  } catch {
    // Invalid browser draft falls back to the verified Excel import.
  }
  return importedRecords.map(normalizeRecord)
}

export function savePurchaseProducts(records: PurchaseProductRecord[]) {
  if (typeof window !== 'undefined') window.localStorage.setItem(STORAGE_KEY, JSON.stringify(records))
}

export function resetPurchaseProducts() {
  if (typeof window !== 'undefined') window.localStorage.removeItem(STORAGE_KEY)
}

export function findPurchaseProduct(records: PurchaseProductRecord[], sku: string) {
  const normalized = sku.trim().toUpperCase().replace(/\s+/g, '')
  return records.find(item => item.sku === normalized)
}

export function purchaseUnitPrice(record: PurchaseProductRecord, quantity: number) {
  const qty = Math.max(1, Math.floor(quantity || 1))
  const tier = record.priceTiers.find(item => qty >= item.minQty && (item.maxQty == null || qty <= item.maxQty))
    || [...record.priceTiers].reverse().find(item => qty >= item.minQty)
  return tier?.unitPriceCny ?? record.purchasePriceCny ?? 0
}

export function purchaseFreightChoices(record: PurchaseProductRecord) {
  return [
    { quantity: 1, totalFreightCny: record.singleFreightCny },
    { quantity: 10, totalFreightCny: record.freight10Cny },
    { quantity: 100, totalFreightCny: record.freight100Cny },
  ].filter((item): item is { quantity: number; totalFreightCny: number } => item.totalFreightCny != null)
    .map(item => ({ ...item, unitFreightCny: item.totalFreightCny / item.quantity }))
}

export function purchaseFreightUnit(record: PurchaseProductRecord, batchQuantity: number) {
  return purchaseFreightChoices(record).find(item => item.quantity === batchQuantity)?.unitFreightCny ?? 0
}

export function purchaseDisplayName(record: PurchaseProductRecord) {
  if (record.name && record.name !== '待补充商品名称') return record.name
  if (record.material) return `${record.material}商品`
  return `商品 ${record.sku}`
}

export function formatPurchaseTiers(record: PurchaseProductRecord) {
  if (!record.priceTiers.length) return '待补采购价格'
  return record.priceTiers.map(item => {
    const range = item.maxQty == null ? `${item.minQty}+件` : `${item.minQty}–${item.maxQty}件`
    return `${range} ¥${item.unitPriceCny.toFixed(2)}`
  }).join('｜')
}

export const purchaseImportMeta = {
  sourceFile: importedPurchaseData.sourceFile,
  sourceSheet: importedPurchaseData.sourceSheet,
  importedAt: importedPurchaseData.importedAt,
  total: importedRecords.length,
}
