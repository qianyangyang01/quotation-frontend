import { customerQuoteSheetTsv, type CustomerQuoteSheet } from '@/data/customerQuoteSheet'
import { withQuoteSheetCopyLock } from './customerQuoteSheetCopyLock'

export async function copyQuoteSheetData(sheet: CustomerQuoteSheet) {
  const text = customerQuoteSheetTsv(sheet)
  if (!globalThis.isSecureContext || !navigator.clipboard?.writeText) {
    throw new Error('当前浏览器不支持复制数据，请使用 Chrome 或 Edge 打开安全页面后重试')
  }
  await withQuoteSheetCopyLock(async () => {
    try { await navigator.clipboard.writeText(text) }
    catch { throw new Error('报价数据未复制成功，请允许剪贴板权限后重试') }
  })
}
