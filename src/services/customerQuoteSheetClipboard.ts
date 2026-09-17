import { customerQuoteSheetTsv, type CustomerQuoteSheet } from '@/data/customerQuoteSheet'
import { withQuoteSheetCopyLock, withSynchronousQuoteSheetCopy } from './customerQuoteSheetCopyLock'

function copyTextDuringClick(text: string) {
  if (typeof document === 'undefined' || typeof document.execCommand !== 'function') return false
  const previous = document.activeElement as HTMLElement | null
  const selection = document.getSelection()
  const ranges = selection ? Array.from({ length: selection.rangeCount }, (_, index) => selection.getRangeAt(index).cloneRange()) : []
  const field = document.createElement('textarea')
  field.value = text
  field.readOnly = true
  field.setAttribute('aria-label', '复制报价数据临时选区')
  field.style.cssText = 'position:fixed;left:0;top:0;width:1px;height:1px;opacity:0;pointer-events:none'
  // A modal makes the body inert; keep the temporary selection in that modal.
  const container = document.querySelector('dialog[open]') ?? document.body
  container.append(field)
  try {
    field.focus({ preventScroll: true })
    field.select()
    return document.execCommand('copy')
  } catch { return false }
  finally {
    field.remove()
    previous?.focus({ preventScroll: true })
    if (selection) {
      selection.removeAllRanges()
      ranges.forEach(range => selection.addRange(range))
    }
  }
}

export async function copyQuoteSheetData(sheet: CustomerQuoteSheet) {
  const text = customerQuoteSheetTsv(sheet)
  if (!globalThis.isSecureContext) {
    throw new Error('当前浏览器不支持复制数据，请使用 Chrome 或 Edge 打开安全页面后重试')
  }
  // Prefer the bounded synchronous command for text. Embedded browsers can
  // leave writeText pending indefinitely when their clipboard bridge stalls.
  if (withSynchronousQuoteSheetCopy(() => copyTextDuringClick(text))) return
  if (!navigator.clipboard?.writeText) throw new Error('当前浏览器不支持复制数据，请使用 Chrome 或 Edge 打开安全页面后重试')
  await withQuoteSheetCopyLock(async () => {
    try { await navigator.clipboard.writeText(text) }
    catch { throw new Error('报价数据未复制成功，请允许剪贴板权限后重试') }
  })
}
