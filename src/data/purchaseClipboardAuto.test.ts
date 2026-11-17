// @vitest-environment happy-dom
import { expect, it } from 'vitest'
import { readAutoPurchaseClipboardResult } from './purchaseClipboardAuto'
import { readUnifiedPurchaseClipboardResult } from './purchaseClipboard'
import { PURCHASE_PASTE_COLUMNS, SPECIAL_PURCHASE_PASTE_COLUMNS, UNIFIED_PURCHASE_PASTE_COLUMNS, SPECIAL_UNIFIED_PURCHASE_PASTE_COLUMNS as columns, applyPurchasePaste, validatePurchasePaste } from './purchasePaste'

// User's Shimo row, preserving every internal blank and the three extra trailing
// blank cells. Image URL/token intentionally omitted; images are not imported.
export const reportedShimoRow = ['','','[图片]','2026.11.14','杨','暂无库存，预计11.16号上架','YT2602031','130','60G（仅供外贸出口销售，禁止国内销售，购买等同默认免责申明','','','','','','1','8','','','','','','','','','6','6','15','否','8','0%','专票13','','','','日用品','有','深圳西之月选品跨境供应链有限公司','','https://detail.1688.com/offer/1098929882459.html?spm=a28888.manage-offer.0.0.26227197mAr9oZ','','','','','','']
const clipboard = (rows: string[][], html: boolean, suffix = '') => ({ getData: (type: string) => type === 'text/html'
  ? html ? `<table>${rows.map(row => `<tr>${row.map(value => `<td>${value === '[图片]' ? '<img src="https://example.com/image.png">' : value.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/\n/g,'<br>')}</td>`).join('')}${suffix}</tr>`).join('')}</table>` : ''
  : rows.map(row => row.map(value => `"${value.replace(/"/g,'""')}"`).join('\t')).join('\r\n') })

it.each([false,true])('reproduces and fixes the exact Shimo whole-row failure, HTML=%s', html => {
  expect(reportedShimoRow).toHaveLength(45)
  const data = clipboard([reportedShimoRow], html)
  expect(() => readUnifiedPurchaseClipboardResult(data,0,{columns,sourceColumns:columns})).toThrow()
  const result = readAutoPurchaseClipboardResult(data,0,columns)
  expect(result.skippedImageColumns).toBe(true)
  const checked = validatePurchasePaste(applyPurchasePaste([],result.rows,0,0,columns),true,columns)
  expect(checked.issues).toEqual([])
  expect(checked.records).toEqual([{sourceRow:1,quotationDate:'2026-11-14',quotationOwner:'杨',notes:reportedShimoRow[5],sku:'YT2602031',weightG:130,size:reportedShimoRow[8],minOrderQty:1,purchasePriceCny:8,singleFreightCny:6,freight10Cny:6,freight100Cny:15,freeShipping:'否',taxIncludedPriceCny:8,taxPoint:0,invoiceType:'专票13',category:'日用品',stockStatus:'有货',factoryInfo:'深圳西之月选品跨境供应链有限公司',sourceLink1:reportedShimoRow[38]}])
})

it.each([false,true])('recognizes all four standard layouts without choosing options, HTML=%s', html => {
  for (const layout of [PURCHASE_PASTE_COLUMNS,SPECIAL_PURCHASE_PASTE_COLUMNS,UNIFIED_PURCHASE_PASTE_COLUMNS,columns]) {
    const values: Record<string,string> = {quotationDate:'2026.11.17',sku:'YT2602036',weightG:'27',minOrderQty:'1',purchasePriceCny:'2.8',tier2MinQty:'18',tier2PriceCny:'2.7',tier3MinQty:'5000',tier3PriceCny:'2.6',singleFreightCny:'2.5',freight10Cny:'3',freight100Cny:'9.8',freeShipping:'否',taxIncludedPriceCny:'2.828',taxPoint:'1%',invoiceType:'普票',category:'厨房用具',stockStatus:'有',factoryInfo:'义乌市迦新家居用品有限公司',sourceLink1:'https://detail.1688.com/offer/948430645601.html'}
    if (layout.some(c=>c[1]==='tier4MinQty')) Object.assign(values,{tier4MinQty:'10000',tier4PriceCny:'2.5'})
    if (layout.some(c=>c[1]==='specialTaxPoint')) Object.assign(values,{specialTaxIncludedPriceCny:'3.164',specialTaxPoint:'13%',specialInvoiceType:'专票'})
    for (const images of [[],['','','[图片]']]) {
      const result = readAutoPurchaseClipboardResult(clipboard([[...images,...layout.map(c=>values[c[1]] || ''),...Array(200).fill('')]],html),0,columns)
      expect(result.rows[0]).toEqual(columns.map(c=>values[c[1]] || ''))
    }
  }
})

it('ignores merged blank suffix and preserves multiline contents and omitted trailing links', () => {
  const row = [...reportedShimoRow];row[5] = '第一行\n第二行'
  const result = readAutoPurchaseClipboardResult(clipboard([row.slice(0,39)],true,'<td colspan="16000"></td>'),0,columns)
  expect(result.rows[0]![2]).toBe('第一行\n第二行')
  expect(result.rows[0]![35]).toBe(row[38])
})

it('does not guess between freight and extra price tiers when the data is ambiguous', () => {
  const row = columns.map(()=>'');row[3]='YT2602036';row[17]='20';row[18]='10'
  expect(()=>readAutoPurchaseClipboardResult(clipboard([row],false),0,columns)).toThrow('无法唯一识别')
})

it('rejects extra data and ambiguous unquoted multiline fragments, without hiding them in blanks or duplicate rows', () => {
  expect(()=>readAutoPurchaseClipboardResult(clipboard([[...reportedShimoRow,'不可丢失']],true),0,columns)).toThrow()
  const broken = {getData:(type:string)=>type==='text/plain' ? reportedShimoRow.join('\t')+'\n换行碎片' : ''}
  expect(()=>readAutoPurchaseClipboardResult(broken,0,columns)).toThrow()
  const invalid=[...reportedShimoRow];invalid[7]='非数字'
  expect(()=>readAutoPurchaseClipboardResult(clipboard([reportedShimoRow,invalid],false),0,columns)).toThrow()
})

it('maps headers with trailing blanks and retains the explicit optimization column rule', () => {
  const result=readAutoPurchaseClipboardResult(clipboard([['SKU','优化后价格','阶梯价2起订量','类别','',''],['YT2602036','999','18','厨房用具']],true),0,columns)
  expect(validatePurchasePaste(result.rows,true,columns).records).toEqual([{sourceRow:1,sku:'YT2602036',tier2MinQty:18,category:'厨房用具'}])
})
