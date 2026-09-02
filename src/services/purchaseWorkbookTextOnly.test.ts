import { describe, expect, it } from 'vitest'
import JSZip from 'jszip'
import { readFile, writeFile } from 'node:fs/promises'
import { MAX_LEGACY_PURCHASE_WORKBOOK_BYTES, stripPurchaseWorkbookImages } from './purchaseWorkbookTextOnly'

function sheetData(xml: string) {
  const match = xml.match(/<sheetData(?:\s[^>]*)?>[\s\S]*?<\/sheetData>/)
  if (!match) throw new Error('worksheet is missing sheetData')
  return match[0]
}

async function workbookSheetData(zip: JSZip) {
  const names = Object.keys(zip.files)
    .filter(name => /^xl\/worksheets\/sheet\d+\.xml$/i.test(name))
    .sort()
  return Promise.all(names.map(async name => [name, sheetData(await zip.file(name)!.async('string'))]))
}

async function workbookFixture(options: { image?: boolean; unsupportedImageRelation?: boolean } = {}) {
  const zip = new JSZip()
  const image = options.image ?? true
  zip.file('[Content_Types].xml', `<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/>${image ? '<Default Extension="png" ContentType="image/png"/><Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>' : ''}</Types>`)
  zip.file('xl/workbook.xml', '<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"/>')
  zip.file('xl/worksheets/sheet1.xml', `<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>SKU</t></is></c><c r="E1" t="inlineStr"><is><t>克重/g</t></is></c><c r="G1" t="inlineStr"><is><t>1件运费</t></is></c><c r="H1" t="inlineStr"><is><t>报价</t></is></c><c r="J1" t="inlineStr"><is><t>含票价</t></is></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>SKU-A</t></is></c><c r="E2"><v>70</v></c><c r="G2"><v>1.7</v></c><c r="H2"><v>6</v></c><c r="J2"><f>H2*1.03</f><v>6.18</v></c></row><row r="4"><c r="A4" t="inlineStr"><is><t>SKU-B</t></is></c><c r="G4"><v>1.1</v></c><c r="H4"><v>9.69</v></c></row></sheetData>${image ? '<drawing r:id="rId1"/>' : ''}</worksheet>`)
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

describe('legacy purchase workbook text-only preparation', () => {
  it('removes embedded media and drawing relationships while preserving cells and hyperlinks', async () => {
    const source = await workbookFixture()
    const originalSheetData = await workbookSheetData(await JSZip.loadAsync(await source.arrayBuffer()))
    const progress: number[] = []
    const result = await stripPurchaseWorkbookImages(source, item => progress.push(item.percent))
    expect(result.removedMediaCount).toBe(1)
    expect(result.optimizedSizeBytes).toBeLessThan(result.originalSizeBytes)
    expect(progress.at(0)).toBe(2)
    expect(progress.at(-1)).toBeGreaterThanOrEqual(90)
    const zip = await JSZip.loadAsync(await result.blob.arrayBuffer())
    expect(Object.keys(zip.files).some(name => /^xl\/(media|drawings)\//i.test(name))).toBe(false)
    expect(await workbookSheetData(zip)).toEqual(originalSheetData)
    expect(await zip.file('xl/worksheets/sheet1.xml')!.async('string')).toContain('<c r="A4" t="inlineStr"><is><t>SKU-B</t></is></c><c r="G4"><v>1.1</v></c>')
    const relationships = await zip.file('xl/worksheets/_rels/sheet1.xml.rels')!.async('string')
    expect(relationships).toContain('https://example.com/media/product')
    expect(relationships).not.toContain('/drawing')
  })

  it('accepts data-only workbooks and allows legacy originals up to 1GB', async () => {
    const result = await stripPurchaseWorkbookImages(await workbookFixture({ image: false }))
    expect(result.removedMediaCount).toBe(0)
    const oversized = await workbookFixture({ image: false })
    Object.defineProperty(oversized, 'size', { value: MAX_LEGACY_PURCHASE_WORKBOOK_BYTES + 1 })
    await expect(stripPurchaseWorkbookImages(oversized)).rejects.toThrow('1GB')
  })

  it('blocks unsupported image relationships instead of uploading the original workbook', async () => {
    await expect(stripPurchaseWorkbookImages(await workbookFixture({ unsupportedImageRelation: true }))).rejects.toThrow('暂不支持的图片关系')
  })

  it('rejects empty or invalid workbooks', async () => {
    await expect(stripPurchaseWorkbookImages(new Blob())).rejects.toThrow('不能为空')
    await expect(stripPurchaseWorkbookImages(new Blob(['not-a-zip']))).rejects.toThrow('损坏、加密或格式无法识别')
  })

  const realWorkbook = process.env.PURCHASE_WORKBOOK
  it.skipIf(!realWorkbook)('reduces the real purchase workbook without media remnants', async () => {
    const bytes = await readFile(realWorkbook!)
    const originalSheetData = await workbookSheetData(await JSZip.loadAsync(bytes))
    const result = await stripPurchaseWorkbookImages(new Blob([bytes]))
    expect(result.removedMediaCount).toBeGreaterThan(0)
    expect(result.reductionPercent).toBeGreaterThanOrEqual(95)
    expect(result.optimizedSizeBytes).toBeLessThanOrEqual(100 * 1024 * 1024)
    const zip = await JSZip.loadAsync(await result.blob.arrayBuffer())
    expect(Object.keys(zip.files).some(name => /^xl\/(media|drawings)\//i.test(name))).toBe(false)
    expect(await workbookSheetData(zip)).toEqual(originalSheetData)
    if (process.env.PURCHASE_WORKBOOK_OUTPUT) {
      await writeFile(process.env.PURCHASE_WORKBOOK_OUTPUT, new Uint8Array(await result.blob.arrayBuffer()))
    }
  }, 180_000)
})
