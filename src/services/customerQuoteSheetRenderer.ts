import brandUrl from '@/assets/quote-sheet/brand.png'
import headerUrl from '@/assets/quote-sheet/table-header.png'
import notesUrl from '@/assets/quote-sheet/notes.png'
import { quoteSheetUsd, type CustomerQuoteSheet } from '@/data/customerQuoteSheet'

export const QUOTE_SHEET_WIDTH = 1536
export const QUOTE_SHEET_ROWS_PER_IMAGE = 24
export type QuoteSheetImage = { blob: Blob; width: number; height: number; firstRow: number; lastRow: number }
const columns = [22, 107, 322, 567, 762, 938, 1081, 1226, 1370, 1515]
const referenceRowHeights = [57, 59, 59, 60, 60, 60]

let assets: Promise<HTMLImageElement[]> | undefined
function loadImage(url: string) {
  return new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new Image()
    const finish = (error?: Error) => {
      clearTimeout(timeout)
      image.onload = null
      image.onerror = null
      if (error) reject(error)
      else resolve(image)
    }
    const timeout = setTimeout(() => finish(new Error('报价单样式素材加载超时，请检查网络后重试')), 15000)
    image.onload = () => finish()
    image.onerror = () => finish(new Error('报价单样式素材加载失败，请重试'))
    image.src = url
  })
}
function loadAssets() {
  assets ??= Promise.all([brandUrl, headerUrl, notesUrl].map(loadImage)).catch(error => {
    assets = undefined
    throw error
  })
  return assets
}
function font(context: CanvasRenderingContext2D, bold = false, size = 22) {
  context.font = `${bold ? 700 : 400} ${size}px Arial, sans-serif`
  context.textBaseline = 'middle'
}
function lines(context: CanvasRenderingContext2D, value: string, width: number) {
  const result: string[] = []
  let line = ''
  for (const character of value) {
    if (character === '\n' || (line && context.measureText(line + character).width > width)) {
      result.push(line.trim())
      line = character === '\n' ? '' : character
    } else line += character
  }
  if (line || !result.length) result.push(line.trim())
  return result
}
function centered(context: CanvasRenderingContext2D, text: string, left: number, right: number, top: number, height: number, bold = false) {
  font(context, bold)
  const wrapped = lines(context, text, right - left - 20)
  context.textAlign = 'center'
  wrapped.forEach((line, index) => context.fillText(line, (left + right) / 2,
    top + height / 2 + (index - (wrapped.length - 1) / 2) * 26))
}
function exportBlob(canvas: HTMLCanvasElement) {
  return new Promise<Blob>((resolve, reject) => canvas.toBlob(blob => {
    if (blob) resolve(blob)
    else reject(new Error('报价图片生成失败，请重试'))
  }, 'image/png'))
}

/** No uploads, storage, downloads, or business writes: results live only in browser memory. */
export async function renderCustomerQuoteSheet(sheet: CustomerQuoteSheet, isCancelled: () => boolean = () => false): Promise<QuoteSheetImage[]> {
  if (sheet.issues.length) throw new Error(sheet.issues.join('；'))
  if (!sheet.rows.length) throw new Error('请先选择报价渠道')
  const [brand, header, notes] = await loadAssets()
  const images: QuoteSheetImage[] = []
  for (let offset = 0; offset < sheet.rows.length; offset += QUOTE_SHEET_ROWS_PER_IMAGE) {
    if (isCancelled()) return []
    const rows = sheet.rows.slice(offset, offset + QUOTE_SHEET_ROWS_PER_IMAGE)
    const finalPage = offset + rows.length === sheet.rows.length
    const canvas = document.createElement('canvas')
    const context = canvas.getContext('2d')
    if (!context) throw new Error('当前浏览器无法生成报价图片')
    font(context)
    const heights = rows.map((row, index) => {
      const values = [String(row.number), row.country, row.provider, row.shippingTime, '1-2 days', ...row.prices.map(quoteSheetUsd)]
      const lineCount = Math.max(...values.map((value, column) => {
        font(context, column === 0 || column >= 5)
        return lines(context, value, columns[column + 1] - columns[column] - 20).length
      }))
      return Math.max(referenceRowHeights[index % 6], lineCount * 26 + 8)
    })
    const tableBottom = 269 + heights.reduce((sum, height) => sum + height, 0)
    canvas.width = QUOTE_SHEET_WIDTH
    canvas.height = tableBottom + (finalPage ? 400 : 40)
    context.fillStyle = '#ffffff'
    context.fillRect(0, 0, canvas.width, canvas.height)
    // These lossless regions are from the user's approved 1536x1024 design. No generated logo or altered terms.
    context.drawImage(brand, 0, 0)
    context.strokeStyle = '#a8a8ad'
    context.lineWidth = 1
    context.beginPath(); context.moveTo(1225.5, 51); context.lineTo(1225.5, 134); context.stroke()
    context.fillStyle = '#222430'
    font(context, true, 26)
    context.textAlign = 'left'
    const agentLines = lines(context, `By Agent: ${sheet.agent}`, 249)
    if (agentLines.length > 2) throw new Error('报价单署名过长，请使用较短的姓名')
    agentLines.forEach((line, index) => context.fillText(line, 1266, (agentLines.length === 1 ? 80 : 64) + index * 29))
    context.fillText(`Date: ${sheet.date}`, 1266, agentLines.length === 1 ? 116 : 127)
    context.fillStyle = '#ff8120'
    context.fillRect(22, 164, 1493, 4)
    context.drawImage(header, 22, 179)
    // Replace ONLY the approved dynamic header cells. No Valid Until or hard-coded quantity survives.
    // Reuse blank orange strips from each original cell, including its exact shading.
    context.drawImage(header, 548, 0, 1, 90, 568, 179, 193, 90)
    context.fillStyle = '#ffffff'
    centered(context, 'Shipping Time', 567, 762, 179, 90, true)
    sheet.quantityLabels.forEach((label, index) => {
      const left = columns[index + 5], right = columns[index + 6]
      context.drawImage(header, left - 22 + 8, 46, 1, 44, left + 1, 225, right - left - 2, 44)
      context.fillStyle = '#ffffff'
      centered(context, label, left, right, 225, 44, true)
    })
    let y = 269
    rows.forEach((row, index) => {
      const height = heights[index]
      context.fillStyle = (offset + index) % 2 ? '#fff2e9' : '#ffffff'
      context.fillRect(22, y, 1493, height)
      const values = [String(row.number), row.country, row.provider, row.shippingTime, '1-2 days', ...row.prices.map(quoteSheetUsd)]
      context.fillStyle = '#111111'
      values.forEach((value, column) => centered(context, value, columns[column], columns[column + 1], y, height, column === 0 || column >= 5))
      y += height
      context.strokeStyle = '#d7d7d7'
      context.lineWidth = 1
      context.beginPath(); context.moveTo(22, y - 0.5); context.lineTo(1515, y - 0.5); context.stroke()
    })
    context.strokeStyle = '#d7d7d7'
    columns.forEach(x => {
      context.beginPath(); context.moveTo(x + 0.5, 269); context.lineTo(x + 0.5, tableBottom); context.stroke()
    })
    if (finalPage) context.drawImage(notes, 22, tableBottom + 14)
    const blob = await exportBlob(canvas)
    // Release backing pixels immediately; the preview/clipboard share the same immutable PNG Blob.
    canvas.width = 0; canvas.height = 0
    if (isCancelled()) return []
    images.push({ blob, width: QUOTE_SHEET_WIDTH, height: tableBottom + (finalPage ? 400 : 40), firstRow: offset + 1, lastRow: offset + rows.length })
  }
  return images
}

export async function copyQuoteSheetImage(blob: Blob) {
  if (!globalThis.isSecureContext || typeof ClipboardItem === 'undefined' || !navigator.clipboard?.write) {
    throw new Error('当前浏览器不支持复制图片，请使用新版 Chrome 或 Edge；也可右键预览图片选择“复制图片”')
  }
  try {
    await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })])
  } catch {
    throw new Error('图片未复制成功，请允许剪贴板权限后重试，或右键预览图片选择“复制图片”')
  }
}
