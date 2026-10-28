import type { QuoteSheetEdits, QuoteSheetSourceRow } from '@/data/customerQuoteSheet'
import { quoteSheetRowKey } from '@/data/customerQuoteSheet'
import { averagePrices, cloneAveragePlans, type AveragePlan } from '@/data/quoteChannelAverage'
import { loadQuotePhotos, releaseQuotePhotos, type QuoteLocalPhoto } from './quoteLocalPhotos'

export type QuoteSheetRecovery = {
  edits: QuoteSheetEdits
  columns: Array<{ quantity: string; legacyCustom: boolean; unit?: string }>
  hiddenRows: string[]; rowOrder: string[]; averages: AveragePlan[]
  averageInputs: Record<string, string>; averageErrors: Record<string, string>
  showPhotos: boolean; photos: Array<{ name: string; type: string; data: string; assetId?: string }>
}
export async function captureRecoveryPhotos(photos: readonly QuoteLocalPhoto[]) {
  return Promise.all(photos.map(async photo => {
    if (!photo.file) throw new Error('图片内容尚未就绪，请稍后再刷新')
    const file = photo.file
    const data = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.onerror = () => reject(new Error('图片备份失败，请重试'))
      reader.readAsDataURL(file)
    })
    return { name: photo.name, type: file.type, data, assetId: photo.assetId }
  }))
}
export async function restoreRecoveryPhotos(saved: QuoteSheetRecovery['photos']) {
  const photos: QuoteLocalPhoto[] = []
  try {
    for (const item of saved) {
      if (!/^data:image\/(png|jpeg|webp|gif);base64,/.test(item.data)) throw new Error('恢复图片格式无效')
      const bytes = Uint8Array.from(atob(item.data.split(',')[1]!), char => char.charCodeAt(0))
      const [photo] = await loadQuotePhotos([new File([bytes], item.name, { type: item.type })], true)
      photo!.assetId = item.assetId
      photos.push(photo!)
    }
    return photos
  } catch (error) { releaseQuotePhotos(photos); throw error }
}
/** Rebuild all system/average amounts; preserve only deliberate customer overrides. */
export function repriceRecoveryAverages(plans: AveragePlan[], rows: QuoteSheetSourceRow[], quantities: number[], price: (row: QuoteSheetSourceRow, quantity: number) => number | null, overrides: Record<string, string> = {}) {
  const byKey = new Map(rows.filter(row => row.available !== false).map(row => [quoteSheetRowKey(row), row]))
  const restored: AveragePlan[] = [], unavailable: string[] = []
  for (const plan of cloneAveragePlans(plans)) {
    if (plan.members.some(member => !byKey.has(member.optionId))) { unavailable.push(plan.provider); continue }
    const members = plan.members.map(member => ({ ...member, sourcePrices: quantities.map(q => price(byKey.get(member.optionId)!, q)) }))
    const systemPrices = averagePrices(members, plan.mode, quantities.length)
    const prices = quantities.map((q, index) => {
      const previous = plan.quantities.indexOf(q)
      return previous >= 0 && (Object.prototype.hasOwnProperty.call(overrides, plan.id + ':' + q) || plan.prices[previous] !== plan.systemPrices[previous]) ? plan.prices[previous]! : systemPrices[index]!
    })
    restored.push({ ...plan, members, quantities: [...quantities], systemPrices, prices })
  }
  return { restored, unavailable }
}
