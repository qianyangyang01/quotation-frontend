import type { FobPatch } from './fobClipboard'
import { decimal } from '@/services/quotationDecimal'

export interface FobManualTier { minQty: string; maxQty: string; price: string }
export interface FobManualDraft {
  sku: string; category: string; quotationOwner: string; quotationDate: string
  weightRaw: string; size: string; color: string; material: string; factoryInfo: string; notes: string
  productImage: string; physicalImage: string
  minOrderQty: string; orderMultiple: string; freightQuantity: string; freightTotal: string
  tiers: FobManualTier[]
}
export function emptyFobManualDraft(): FobManualDraft {
  return { sku: '', category: '', quotationOwner: '', quotationDate: new Date().toLocaleDateString('sv-SE'),
    weightRaw: '', size: '', color: '', material: '', factoryInfo: '', notes: '', productImage: '', physicalImage: '', minOrderQty: '',
    orderMultiple: '1', freightQuantity: '100', freightTotal: '', tiers: [{ minQty: '', maxQty: '', price: '' }] }
}
function quantity(value: string, label: string) {
  if (!/^\d+$/.test(value.trim()) || !Number.isSafeInteger(Number(value)) || Number(value) < 1 || Number(value) > 100_000_000)
    throw new Error(`${label}须为1至100000000之间的整数`)
  return Number(value)
}
function amount(value: string, label: string) {
  if (!/^\d+(?:\.\d+)?$/.test(value.trim()) || !Number.isFinite(Number(value)) || Number(value) > 100_000_000)
    throw new Error(`请填写有效的${label}，免费请明确填写0`)
  return decimal(value).toFixed()
}
export function fobManualPatch(draft: FobManualDraft): FobPatch {
  const sku = draft.sku.normalize('NFKC').toUpperCase().replace(/[\s\u200b]+/g, '')
  if (!/^[A-Z0-9._/-]{1,96}$/.test(sku) || /^(TESTP|TEST|DEMO|MOCK|AUTO-)/.test(sku)) throw new Error('请填写有效的正式SKU')
  const minimum = quantity(draft.minOrderQty, '起订量'), multiple = quantity(draft.orderMultiple, '下单倍数')
  if (!draft.tiers.length) throw new Error('请至少填写一档采购价格')
  let previousEnd: number | null = null
  const prices = draft.tiers.map((tier, i) => {
    const low = quantity(tier.minQty, `第${i + 1}档数量起点`)
    const high = tier.maxQty.trim() ? quantity(tier.maxQty, `第${i + 1}档数量终点`) : null
    if ((i === 0 && low !== minimum) || (i > 0 && (previousEnd == null || low !== previousEnd + 1)))
      throw new Error('第一档须从起订量开始，各阶梯须连续且不重叠')
    if (high != null && high < low) throw new Error('阶梯数量终点不能小于起点')
    previousEnd = high
    return `${high == null ? low + '件起' : low + '-' + high + '件'}单价${amount(tier.price, '未税采购单价')}`
  })
  const freightQty = quantity(draft.freightQuantity, '运费对应数量')
  if (freightQty < 100) throw new Error('FOB沿用现有批量运费规则，请填写100件或更大批量的总运费')
  const patch: FobPatch = { sku, moqRaw: `${minimum}件起订;${multiple}的倍数下单`,
    priceRaw: prices.join('\n'), freightRaw: `${freightQty}件运费${amount(draft.freightTotal, '国内总运费')}` }
  for (const field of ['category', 'quotationOwner', 'quotationDate', 'weightRaw', 'size', 'color', 'material', 'factoryInfo', 'notes', 'productImage', 'physicalImage'] as const)
    if (draft[field].trim()) patch[field] = draft[field].trim()
  return patch
}
