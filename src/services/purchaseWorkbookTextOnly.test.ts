import { describe, expect, it } from 'vitest'
import JSZip from 'jszip'
import { readFile, writeFile } from 'node:fs/promises'
import { stripPurchaseWorkbookImages } from './purchaseWorkbookTextOnly'

async function workbookFixture(options: { image?: boolean; unsupportedImageRelation?: boolean } = {}) {
  const zip = new JSZip()
  const image = options.image ?? true
  zip.file('[Content_Types].xml', `<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/>${image ? '<Default Extension="png" ContentType="image/png"/><Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>' : ''}</Types>`)
  zip.file('xl/workbook.xml', '<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"/>')
  zip.file('xl/worksheets/sheet1.xml', `<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheetData><row r="1"><c r="A1"><v>42</v></c></row></sheetData>${image ? '<drawing r:id="rId1"/>' : ''}</worksheet>`)
  zip.file('xl/worksheets/_rels/sheet1.xml.rels', `<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId9" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://example.com/media/product" TargetMode="External"/>${image ? '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/>' : ''}</Relationships>`)
  if (image) {
    zip.file('xl/drawings/drawing1.xml', '<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"/>')
    zip.file('xl/drawings/_rels/drawing1.xml.rels', '<Relationships><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image1.png"/></Relationships>')
    zip.file('xl/media/image1.png', new Uint8Array(32_000).fill(7))
  }
  if (options.unsupportedImageRelation) {
    zip.file('xl/_rels/workbook.xml.rels', '<Relationships><Relationship Id="rIdImage" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/></Relationships>')
  }
  return new Blob([await zip.generateAsync({ type: 'uint8array' })], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
}

describe('purchase workbook text-only preparation', () => {
  it('removes embedded media and drawing relationships while preserving cell data and hyperlinks', async () => {
    const source = await workbookFixture()
    const progress: number[] = []
    const result = await stripPurchaseWorkbookImages(source, item => progress.push(item.percent))
    expect(result.removedMediaCount).toBe(1)
    expect(result.optimizedSizeBytes).toBeLessThan(result.originalSizeBytes)
    expect(progress.at(0)).toBe(2)
    expect(progress.at(-1)).toBeGreaterThanOrEqual(90)
    const zip = await JSZip.loadAsync(await result.blob.arrayBuffer())
    expect(Object.keys(zip.files).some(name => /^xl\/(media|drawings)\//i.test(name))).toBe(false)
    const sheet = await zip.file('xl/worksheets/sheet1.xml')!.async('string')
    expect(sheet).toContain('<v>42</v>')
    expect(sheet).not.toMatch(/<drawing\b/i)
    const relationships = await zip.file('xl/worksheets/_rels/sheet1.xml.rels')!.async('string')
    expect(relationships).toContain('https://example.com/media/product')
    expect(relationships).not.toContain('/drawing')
    const contentTypes = await zip.file('[Content_Types].xml')!.async('string')
    expect(contentTypes).not.toContain('/xl/drawings/')
  })

  it('accepts data-only workbooks without inventing media', async () => {
    const result = await stripPurchaseWorkbookImages(await workbookFixture({ image: false }))
    expect(result.removedMediaCount).toBe(0)
    const zip = await JSZip.loadAsync(await result.blob.arrayBuffer())
    expect(await zip.file('xl/worksheets/sheet1.xml')!.async('string')).toContain('<v>42</v>')
  })

  it('keeps truthful size metadata when repackaging a compressed workbook increases its size', async () => {
    const fixture = await workbookFixture({ image: false })
    const originalZip = await JSZip.loadAsync(await fixture.arrayBuffer())
    const source = new Blob([await originalZip.generateAsync({
      type: 'uint8array', compression: 'DEFLATE', compressionOptions: { level: 9 }, streamFiles: false,
    })])
    const result = await stripPurchaseWorkbookImages(source)
    expect(result.blob.size).toBeGreaterThan(source.size)
    expect(result.originalSizeBytes).toBe(source.size)
    expect(result.optimizedSizeBytes).toBe(result.blob.size)
    expect(result.removedMediaCount).toBe(0)
    const zip = await JSZip.loadAsync(await result.blob.arrayBuffer(), { checkCRC32: true })
    expect(await zip.file('xl/worksheets/sheet1.xml')!.async('string'))
      .toBe(await originalZip.file('xl/worksheets/sheet1.xml')!.async('string'))
  })

  it('blocks unsupported image relations instead of uploading the original workbook', async () => {
    await expect(stripPurchaseWorkbookImages(await workbookFixture({ unsupportedImageRelation: true }))).rejects.toThrow('暂不支持的图片关系')
  })

  it('rejects empty or invalid workbooks', async () => {
    await expect(stripPurchaseWorkbookImages(new Blob())).rejects.toThrow('不能为空')
    await expect(stripPurchaseWorkbookImages(new Blob(['not-a-zip']))).rejects.toThrow('损坏、加密或格式无法识别')
  })

  const realWorkbook = process.env.PURCHASE_WORKBOOK
  it.skipIf(!realWorkbook)('reduces the real purchase workbook by at least 95 percent without media remnants', async () => {
    const bytes = await readFile(realWorkbook!)
    const result = await stripPurchaseWorkbookImages(new Blob([bytes]))
    expect(result.removedMediaCount).toBeGreaterThan(0)
    expect(result.reductionPercent).toBeGreaterThanOrEqual(95)
    expect(result.optimizedSizeBytes).toBeLessThanOrEqual(2 * 1024 * 1024)
    const zip = await JSZip.loadAsync(await result.blob.arrayBuffer())
    expect(Object.keys(zip.files).some(name => /^xl\/(media|drawings)\//i.test(name))).toBe(false)
    if (process.env.PURCHASE_WORKBOOK_OUTPUT) {
      await writeFile(process.env.PURCHASE_WORKBOOK_OUTPUT, new Uint8Array(await result.blob.arrayBuffer()))
    }
  }, 120_000)
})
