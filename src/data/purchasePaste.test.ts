import { describe, expect, it } from 'vitest'
import { applyPurchasePaste, emptyPurchasePasteRow, parsePurchaseClipboard, PURCHASE_PASTE_COLUMNS, validatePurchasePaste } from './purchasePaste'

function valid() {
  const row = emptyPurchasePasteRow()
  row[3] = 'P260905-1'; row[4] = '50'; row[11] = '1'; row[12] = '9.24'; row[17] = '3.5'; row[18] = '3.5'
  return row
}
describe('purchase data-only paste', () => {
  it('retains the supplied 32 column order including material and tax point', () => {
    expect(PURCHASE_PASTE_COLUMNS.map(c => c[0])).toEqual(['报价日期*','报价人*','备注','SKU','克重(g)*','尺码','颜色','材质','长(cm)*','宽(cm)*','高(cm)*','起订量(件)*','基准采购单价(CNY/件)*','阶梯价2起订量','阶梯价2(CNY/件)','阶梯价3起订量','阶梯价3(CNY/件)','1件总运费(CNY)','10件总运费(CNY)','100件总运费(CNY)','是否包邮','含票价(CNY/件)','票点','票类型','类别','是否有货*','工厂信息','审核备注','货源链接1','货源链接2','货源链接3','相似货源'])
  })
  it('parses Excel quoted multiline cells, tabs, escaped quotes and empty columns', () => {
    expect(parsePurchaseClipboard('2026.9.3\t\t"备注\r\n含""引号""\t内容"\tP-1\t\r\n')).toEqual([['2026.9.3','','备注\n含"引号"\t内容','P-1','']])
    expect(() => parsePurchaseClipboard('"未闭合')).toThrow('引号')
  })
  it('skips blank cells without shifting columns or mutating existing data', () => {
    const grid = [valid()]; grid[0]![5] = 'M'
    const result = applyPurchasePaste(grid, [['75', '', '红色'], ['80', 'L', '蓝色']], 0, 4)
    expect(result[0]!.slice(4,7)).toEqual(['75','M','红色'])
    expect(result[1]!.slice(4,7)).toEqual(['80','L','蓝色'])
    expect(grid[0]![4]).toBe('50')
    expect(() => applyPurchasePaste(grid, [['a','b']], 0,31)).toThrow('超出')
    expect(() => applyPurchasePaste(grid, [['a']], 100,0)).toThrow('100')
  })
  it('allows optional blanks and normalizes real template dates, material and tax points', () => {
    const row = valid(); row[0]='2026.9.3'; row[7]='棉\n锦纶'; row[22]='13%'; row[25]='有'
    const result = validatePurchasePaste([row,emptyPurchasePasteRow()])
    expect(result.canSave).toBe(true); expect(result.records).toHaveLength(1)
    expect(result.records[0]).toMatchObject({quotationDate:'2026-09-03',material:'棉\n锦纶',taxPoint:.13,lengthCm:null,quoteReady:true,stockStatus:'有货'})
  })
  it('requires quotation inputs and explicit freight, permits genuine zero prices', () => {
    const row=valid(); row[18]=''
    expect(validatePurchasePaste([row]).issues.some(i=>i.column===18)).toBe(true)
    row[20]='是'; row[12]='0'
    expect(validatePurchasePaste([row]).canSave).toBe(true)
    row[4]='0'; row[11]='1.5'; row[12]=''
    expect(validatePurchasePaste([row]).canSave).toBe(false)
  })
  it('rejects malformed numbers, partial dimensions, tiers, invalid dates and duplicate SKU', () => {
    const row=valid(); row[8]='5'; row[13]='1'; row[14]='8'; row[0]='2026.2.30'; row[19]='0x20'
    const result=validatePurchasePaste([row,valid()])
    for (const col of [9,10,13,0,19,3]) expect(result.issues.some(i=>i.column===col)).toBe(true)
    expect(result.canSave).toBe(false)
  })
})
