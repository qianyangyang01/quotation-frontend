import JSZip from 'jszip'

export const MAX_LEGACY_PURCHASE_WORKBOOK_BYTES = 1024 * 1024 * 1024
export const MAX_OPTIMIZED_PURCHASE_WORKBOOK_BYTES = 100 * 1024 * 1024

export interface TextOnlyWorkbookProgress {
  stage: '正在检查旧数据工作簿' | '正在移除图片' | '正在生成无图数据文件'
  percent: number
}

export interface TextOnlyWorkbookResult {
  blob: Blob
  originalSizeBytes: number
  optimizedSizeBytes: number
  removedMediaCount: number
  reductionPercent: number
}

const mediaPath = /^xl\/media\//i
const drawingPath = /^xl\/drawings\//i
const worksheetPath = /^xl\/worksheets\/sheet[^/]+\.xml$/i
const worksheetRelationshipPath = /^xl\/worksheets\/_rels\/sheet[^/]+\.xml\.rels$/i
const relationshipTag = /<Relationship\b[^>]*\/?\s*>/gi
const drawingElement = /<(?:\w+:)?(?:drawing|legacyDrawing|legacyDrawingHF)\b[^>]*\/?\s*>/gi
const contentTypeOverride = /<Override\b[^>]*\/?\s*>/gi

function attribute(tag: string, name: string) {
  const match = tag.match(new RegExp(`\\b${name}=(['"])(.*?)\\1`, 'i'))
  return match?.[2] ?? ''
}

function referencesRemovedDrawing(tag: string) {
  const type = attribute(tag, 'Type').toLowerCase()
  const target = attribute(tag, 'Target').replace(/\\/g, '/').toLowerCase()
  const external = attribute(tag, 'TargetMode').toLowerCase() === 'external'
  return type.endsWith('/drawing') || type.endsWith('/image') || (!external && (/(?:^|\/)drawings\//.test(target) || /(?:^|\/)media\//.test(target)))
}

function ensureRequiredParts(zip: JSZip) {
  if (!zip.file('[Content_Types].xml') || !zip.file('xl/workbook.xml')) {
    throw new Error('文件不是有效的 Excel .xlsx 工作簿')
  }
}

async function removeDrawingRelationships(zip: JSZip) {
  const relationshipNames = Object.keys(zip.files).filter(name => name.toLowerCase().endsWith('.rels') && !drawingPath.test(name))
  for (const name of relationshipNames) {
    const entry = zip.file(name)
    if (!entry) continue
    const xml = await entry.async('string')
    const removed = [...xml.matchAll(relationshipTag)].filter(match => referencesRemovedDrawing(match[0]))
    if (!removed.length) continue
    if (!worksheetRelationshipPath.test(name)) {
      throw new Error(`工作簿包含暂不支持的图片关系：${name}`)
    }
    zip.file(name, xml.replace(relationshipTag, tag => referencesRemovedDrawing(tag) ? '' : tag))
  }
}

async function removeWorksheetDrawingElements(zip: JSZip) {
  const sheetNames = Object.keys(zip.files).filter(name => worksheetPath.test(name))
  for (const name of sheetNames) {
    const entry = zip.file(name)
    if (!entry) continue
    const xml = await entry.async('string')
    drawingElement.lastIndex = 0
    if (drawingElement.test(xml)) {
      drawingElement.lastIndex = 0
      zip.file(name, xml.replace(drawingElement, ''))
    }
    drawingElement.lastIndex = 0
  }
}

async function removeDrawingContentTypes(zip: JSZip) {
  const entry = zip.file('[Content_Types].xml')
  if (!entry) return
  const xml = await entry.async('string')
  zip.file('[Content_Types].xml', xml.replace(contentTypeOverride, tag => {
    const part = attribute(tag, 'PartName').replace(/\\/g, '/').toLowerCase()
    return part.startsWith('/xl/drawings/') ? '' : tag
  }))
}

async function verifyTextOnlyPackage(zip: JSZip) {
  const paths = Object.keys(zip.files)
  if (paths.some(name => mediaPath.test(name) || drawingPath.test(name))) {
    throw new Error('图片或绘图文件未完全移除')
  }
  for (const name of paths.filter(item => item.toLowerCase().endsWith('.rels'))) {
    const entry = zip.file(name)
    if (!entry) continue
    const xml = await entry.async('string')
    if ([...xml.matchAll(relationshipTag)].some(match => referencesRemovedDrawing(match[0]))) {
      throw new Error(`图片关系未完全移除：${name}`)
    }
  }
  for (const name of paths.filter(item => worksheetPath.test(item))) {
    const entry = zip.file(name)
    if (!entry) continue
    const xml = await entry.async('string')
    drawingElement.lastIndex = 0
    if (drawingElement.test(xml)) throw new Error(`工作表绘图引用未完全移除：${name}`)
    drawingElement.lastIndex = 0
  }
  const contentTypes = await zip.file('[Content_Types].xml')!.async('string')
  if ([...contentTypes.matchAll(contentTypeOverride)].some(match => attribute(match[0], 'PartName').toLowerCase().startsWith('/xl/drawings/'))) {
    throw new Error('绘图内容类型未完全移除')
  }
}

function memoryError(error: unknown) {
  return error instanceof RangeError || (error instanceof Error && /memory|allocation|array buffer/i.test(error.message))
}

function userFacingError(error: unknown, message: string) {
  const result = error instanceof Error ? error : new Error(String(error))
  result.message = message
  return result
}

export async function stripPurchaseWorkbookImages(source: Blob, onProgress?: (progress: TextOnlyWorkbookProgress) => void): Promise<TextOnlyWorkbookResult> {
  if (source.size <= 0 || source.size > MAX_LEGACY_PURCHASE_WORKBOOK_BYTES) {
    throw new Error('旧数据 Excel 文件不能为空且不能超过1GB')
  }
  onProgress?.({ stage: '正在检查旧数据工作簿', percent: 2 })
  let zip: JSZip
  try {
    // CRC checking inflates every media entry. The package is verified structurally below,
    // while avoiding a second 700MB+ pass for image-heavy legacy workbooks.
    zip = await JSZip.loadAsync(await source.arrayBuffer())
  } catch (error) {
    if (memoryError(error)) throw userFacingError(error, '旧数据工作簿过大，本机内存不足以生成无图文件，请关闭其他大型程序后重试')
    throw userFacingError(error, 'Excel 文件损坏、加密或格式无法识别')
  }
  ensureRequiredParts(zip)
  const names = Object.keys(zip.files)
  const mediaEntries = names.filter(name => mediaPath.test(name))
  const mediaNames = mediaEntries.filter(name => !zip.files[name]?.dir)
  const drawingNames = names.filter(name => drawingPath.test(name))
  onProgress?.({ stage: '正在移除图片', percent: 12 })
  await removeDrawingRelationships(zip)
  await removeWorksheetDrawingElements(zip)
  await removeDrawingContentTypes(zip)
  for (const name of [...mediaEntries, ...drawingNames]) zip.remove(name)
  await verifyTextOnlyPackage(zip)
  onProgress?.({ stage: '正在生成无图数据文件', percent: 18 })
  let blob: Blob
  try {
    blob = await zip.generateAsync({
      type: 'blob',
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      compression: 'DEFLATE',
      compressionOptions: { level: 6 },
      streamFiles: true,
    }, metadata => onProgress?.({
      stage: '正在生成无图数据文件',
      percent: Math.min(99, 18 + Math.round(metadata.percent * 0.81)),
    }))
  } catch (error) {
    if (memoryError(error)) throw userFacingError(error, '旧数据工作簿过大，本机内存不足以生成无图文件，请关闭其他大型程序后重试')
    throw error
  }
  if (blob.size > MAX_OPTIMIZED_PURCHASE_WORKBOOK_BYTES) {
    throw new Error('去除图片后的旧数据文件仍超过100MB，请按工作表拆分后导入')
  }
  const reductionPercent = Math.max(0, Math.round((1 - blob.size / source.size) * 1000) / 10)
  return { blob, originalSizeBytes: source.size, optimizedSizeBytes: blob.size, removedMediaCount: mediaNames.length, reductionPercent }
}
