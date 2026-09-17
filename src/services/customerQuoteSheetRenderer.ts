import brandUrl from '@/assets/quote-sheet/brand.png'
import headerUrl from '@/assets/quote-sheet/table-header.png'
import notesUrl from '@/assets/quote-sheet/notes.png'
import { CUSTOMER_QUOTE_NOTES, MAX_QUOTE_SHEET_COLUMNS, quoteSheetUsd, quoteSheetColumns, quoteSheetCell, type QuoteSheetOptionalColumn, type CustomerQuoteSheet } from '@/data/customerQuoteSheet'
import { withQuoteSheetCopyLock } from './customerQuoteSheetCopyLock'

export const QUOTE_SHEET_WIDTH = 1536
export const QUOTE_SHEET_ROWS_PER_IMAGE = 24
export type QuoteSheetImage = { blob: Blob; width: number; height: number; firstRow: number; lastRow: number }
export function quoteSheetLayout(priceColumns: number, hiddenColumns: readonly QuoteSheetOptionalColumn[] = []) {
  if (!Number.isInteger(priceColumns) || priceColumns < 1 || priceColumns > MAX_QUOTE_SHEET_COLUMNS) throw new Error('价格列须为 1–10 列')
  const fixedColumns = quoteSheetColumns(hiddenColumns)
  const columns = [22]
  fixedColumns.forEach(column => columns.push(columns[columns.length - 1] + column.width))
  const priceStart = columns[columns.length - 1]
  const width = Math.max(QUOTE_SHEET_WIDTH, priceStart + priceColumns * 144 + 21)
  const right = width - 21
  columns.push(...Array.from({ length: priceColumns }, (_, index) => priceStart + (right - priceStart) * (index + 1) / priceColumns))
  return { width, right, columns, fixedColumns }
}
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
  return new Promise<Blob>((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('报价图片生成超时，请重试')), 15000)
    try {
      canvas.toBlob(blob => {
        clearTimeout(timeout)
        if (blob) resolve(blob)
        else reject(new Error('报价图片生成失败，请重试'))
      }, 'image/png')
    } catch (error) { clearTimeout(timeout); reject(error) }
  })
}

/** No uploads, storage, downloads, or business writes: results live only in browser memory. */
export async function renderCustomerQuoteSheet(sheet: CustomerQuoteSheet, isCancelled: () => boolean = () => false): Promise<QuoteSheetImage[]> {
  if (sheet.issues.length) throw new Error(sheet.issues.join('；'))
  if (!sheet.rows.length) throw new Error('请先选择报价渠道')
  const { width, right, columns, fixedColumns } = quoteSheetLayout(sheet.quantityLabels.length, sheet.hiddenColumns)
  const fixedCount = fixedColumns.length
  const priceStart = columns[fixedCount]
  if (sheet.rows.some(row => row.prices.length !== sheet.quantityLabels.length)) throw new Error('价格数量与表头不一致，请重新预览')
  const noteTexts = sheet.notes ?? [...CUSTOMER_QUOTE_NOTES]
  const useNotesAsset = width === QUOTE_SHEET_WIDTH && JSON.stringify(noteTexts) === JSON.stringify(CUSTOMER_QUOTE_NOTES)
  const [brand, header, notes] = await loadAssets()
  const images: QuoteSheetImage[] = []
  for (let offset = 0; offset < sheet.rows.length; offset += QUOTE_SHEET_ROWS_PER_IMAGE) {
    if (isCancelled()) return []
    const rows = sheet.rows.slice(offset, offset + QUOTE_SHEET_ROWS_PER_IMAGE)
    const finalPage = offset + rows.length === sheet.rows.length
    const canvas = document.createElement('canvas')
    try {
    const context = canvas.getContext('2d')
    if (!context) throw new Error('当前浏览器无法生成报价图片')
    font(context, true)
    const quantityHeaderHeight = Math.max(44, ...sheet.quantityLabels.map((label, index) => lines(context, label, columns[index + fixedCount + 1] - columns[index + fixedCount] - 20).length * 26 + 8))
    const tableTop = 225 + quantityHeaderHeight
    const heights = rows.map((row, index) => {
      const values = [...fixedColumns.map(column => quoteSheetCell(row, column.key)), ...row.prices.map(quoteSheetUsd)]
      const lineCount = Math.max(...values.map((value, column) => {
        font(context, column === 0 || column >= fixedCount)
        return lines(context, value, columns[column + 1] - columns[column] - 20).length
      }))
      return Math.max(referenceRowHeights[index % 6], lineCount * 26 + 8)
    })
    const tableBottom = tableTop + heights.reduce((sum, height) => sum + height, 0)
    font(context, false, 20)
    const noteLines = noteTexts.map(note => lines(context, note, right - 149))
    const noteHeights = noteLines.map(note => Math.max(52, note.length * 25 + 24))
    const notesHeight = useNotesAsset ? 400 : 14 + 56 + noteHeights.reduce((sum, height) => sum + height, 0) + 40
    const imageHeight = tableBottom + (finalPage ? notesHeight : 40)
    // Reject pathological pasted line breaks before allocating hundreds of MB of pixels.
    if (imageHeight > 16384 || width * imageHeight > 24_000_000) throw new Error('报价单内容过长，请减少说明中的换行或缩短字段后重试')
    canvas.width = width
    canvas.height = imageHeight
    context.fillStyle = '#ffffff'
    context.fillRect(0, 0, canvas.width, canvas.height)
    // These lossless regions are from the user's approved 1536x1024 design. No generated logo or altered terms.
    const metadataOffset = width - QUOTE_SHEET_WIDTH
    if (metadataOffset) {
      context.drawImage(brand, 0, 0, 430, 164, 0, 0, 430, 164)
      context.drawImage(brand, 430, 0, 795, 164, 430 + metadataOffset / 2, 0, 795, 164)
    } else context.drawImage(brand, 0, 0)
    if (sheet.title !== undefined && sheet.title !== 'JerryFulfillment Quote Sheet') {
      context.fillStyle = '#fff'; context.fillRect(430 + metadataOffset / 2, 0, 795, 160)
      font(context, true, 46)
      const titleLines = lines(context, sheet.title, 755)
      if (titleLines.length > 2) throw new Error('报价单标题过长，请缩短后重试')
      context.fillStyle = '#202532'; context.textAlign = 'center'
      titleLines.forEach((line, index) => context.fillText(line, 827 + metadataOffset / 2, 80 + (index - (titleLines.length - 1) / 2) * 52))
    }
    context.strokeStyle = '#a8a8ad'
    context.lineWidth = 1
    context.beginPath(); context.moveTo(1225.5 + metadataOffset, 51); context.lineTo(1225.5 + metadataOffset, 134); context.stroke()
    context.fillStyle = '#222430'
    font(context, true, 26)
    context.textAlign = 'left'
    const agentLines = lines(context, `By Agent: ${sheet.agent}`, 249)
    if (agentLines.length > 2) throw new Error('报价单署名过长，请使用较短的姓名')
    agentLines.forEach((line, index) => context.fillText(line, 1266 + metadataOffset, (agentLines.length === 1 ? 80 : 64) + index * 29))
    context.fillText(`Date: ${sheet.date}`, 1266 + metadataOffset, agentLines.length === 1 ? 116 : 127)
    context.fillStyle = '#ff8120'
    context.fillRect(22, 164, right - 22, 4)
    // Reuse the approved orange shading while laying out only visible columns.
    context.drawImage(header, 548, 0, 1, 90, 22, 179, right - 22, tableTop - 179)
    context.fillStyle = '#ffffff'
    fixedColumns.forEach((column, index) => centered(context, column.key === 'processingTime' ? 'Processing\nTime' : column.label, columns[index], columns[index + 1], 179, tableTop - 179, true))
    centered(context, 'Quote by Quantity (USD)', priceStart, right, 179, 46, true)
    sheet.quantityLabels.forEach((label, index) => {
      centered(context, label, columns[index + fixedCount], columns[index + fixedCount + 1], 225, quantityHeaderHeight, true)
    })
    context.strokeStyle = '#fff'; context.lineWidth = 1
    context.beginPath(); context.moveTo(priceStart, 225); context.lineTo(right, 225); context.stroke()
    columns.forEach((x, index) => {
      context.beginPath(); context.moveTo(x, index <= fixedCount ? 179 : 225); context.lineTo(x, tableTop); context.stroke()
    })
    let y = tableTop
    rows.forEach((row, index) => {
      const height = heights[index]
      context.fillStyle = (offset + index) % 2 ? '#fff2e9' : '#ffffff'
      context.fillRect(22, y, right - 22, height)
      const values = [...fixedColumns.map(column => quoteSheetCell(row, column.key)), ...row.prices.map(quoteSheetUsd)]
      context.fillStyle = '#111111'
      values.forEach((value, column) => centered(context, value, columns[column], columns[column + 1], y, height, column === 0 || column >= fixedCount))
      y += height
      context.strokeStyle = '#d7d7d7'
      context.lineWidth = 1
      context.beginPath(); context.moveTo(22, y - 0.5); context.lineTo(right, y - 0.5); context.stroke()
    })
    context.strokeStyle = '#d7d7d7'
    columns.forEach(x => {
      context.beginPath(); context.moveTo(x + 0.5, tableTop); context.lineTo(x + 0.5, tableBottom); context.stroke()
    })
    if (finalPage && useNotesAsset) context.drawImage(notes, 22, tableBottom + 14)
    else if (finalPage) {
      let noteY = tableBottom + 14
      context.fillStyle = '#fa8020'; context.fillRect(22, noteY, right - 22, 56)
      context.fillStyle = '#fff'; font(context, true, 30); context.textAlign = 'center'
      context.fillText('IMPORTANT NOTES', (22 + right) / 2, noteY + 28)
      noteY += 56
      noteLines.forEach((wrapped, index) => {
        const height = noteHeights[index]
        context.fillStyle = '#ff8120'; font(context, true, 30); context.textAlign = 'center'
        context.fillText(String(index + 1), 64, noteY + height / 2)
        context.fillStyle = '#202532'; font(context, false, 20); context.textAlign = 'left'
        wrapped.forEach((line, lineIndex) => context.fillText(line, 130, noteY + 23 + lineIndex * 25))
        context.strokeStyle = '#d7d7d7'; context.strokeRect(22, noteY, right - 22, height)
        context.beginPath(); context.moveTo(107, noteY); context.lineTo(107, noteY + height); context.stroke()
        noteY += height
      })
    }
    const blob = await exportBlob(canvas)
    if (isCancelled()) return []
    images.push({ blob, width, height: imageHeight, firstRow: offset + 1, lastRow: offset + rows.length })
    } finally {
      // Include failed/cancelled renders, not just successful PNG encoding.
      canvas.width = 0; canvas.height = 0
    }
  }
  return images
}

export async function copyQuoteSheetImage(blob: Blob) {
  if (!globalThis.isSecureContext || typeof ClipboardItem === 'undefined' || !navigator.clipboard?.write) {
    throw new Error('当前浏览器不支持复制图片，请使用新版 Chrome 或 Edge；也可右键预览图片选择“复制图片”')
  }
  await withQuoteSheetCopyLock(async () => {
    try {
      await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })])
    } catch {
      throw new Error('图片未复制成功，请允许剪贴板权限后重试，或右键预览图片选择“复制图片”')
    }
  })
}
