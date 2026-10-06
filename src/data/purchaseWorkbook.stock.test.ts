// @vitest-environment happy-dom
import { expect, it } from 'vitest'
import JSZip from 'jszip'
import { parsePurchaseWorkbook, PURCHASE_WORKBOOK_HEADERS } from './purchaseWorkbook'

it.each(['是否有货', '是否有货*'])('imports custom and empty stock notes with compatible header %s', async header => {
  const headers: string[] = [...PURCHASE_WORKBOOK_HEADERS]
  headers[24] = header
  const cell = (value: string, col: number, row: number) => {
    const name = col < 26 ? String.fromCharCode(65 + col) : 'A' + String.fromCharCode(65 + col - 26)
    return `<c r="${name}${row}" t="inlineStr"><is><t>${value}</t></is></c>`
  }
  const values = ['', '少量现货，7天补货', '无货'].map((stockStatus, index) => {
    const row = headers.map(() => '')
    row[0] = `STOCK-26000${index}`; row[8] = '100'; row[12] = '1'; row[13] = '12'; row[24] = stockStatus
    return row
  })
  const rows = [headers, ...values].map((values, index) => `<row r="${index + 1}">${values.map((value, col) => cell(value, col, index + 1)).join('')}</row>`).join('')
  const zip = new JSZip()
  zip.file('xl/workbook.xml', '<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="采购产品导入" r:id="rId1"/></sheets></workbook>')
  zip.file('xl/_rels/workbook.xml.rels', '<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>')
  zip.file('xl/worksheets/sheet1.xml', `<worksheet><sheetData>${rows}</sheetData></worksheet>`)
  const buffer = await zip.generateAsync({ type: 'arraybuffer' })
  const result = await parsePurchaseWorkbook(new File([buffer], 'stock.xlsx'), [])
  expect(result.records.map(record => record.stockStatus)).toEqual(['', '少量现货，7天补货', '无货'])
  expect(result.records.every(record => record.quoteReady)).toBe(true)
  expect(result.issues.filter(issue => issue.field.includes('是否有货'))).toEqual([])
})
