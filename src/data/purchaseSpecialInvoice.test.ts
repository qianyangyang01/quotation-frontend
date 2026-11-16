// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { readUnifiedPurchaseClipboardResult } from './purchaseClipboard'
import { SPECIAL_UNIFIED_PURCHASE_PASTE_COLUMNS as columns, UNIFIED_PURCHASE_PASTE_COLUMNS as oldColumns, validatePurchasePaste } from './purchasePaste'
import { normalizePurchaseRecord } from './purchaseStore'
import { validateSpecialInvoice } from './purchaseSpecialInvoice'
import { purchasePriceBreakdown } from '@/services/quotationCalculator'
import attachmentHeaders from '../../tests/fixtures/purchase-special-invoice-headers.json'

const data = { sku: 'INV-001', weightG: '100', minOrderQty: '1', purchasePriceCny: '100', freeShipping: '是', taxIncludedPriceCny: '103', taxPoint: '3%', invoiceType: '普票', specialTaxIncludedPriceCny: '113', specialTaxPoint: '13%', specialInvoiceType: '专票13', category: '女装', stockStatus: '有货', sourceLink1: 'https://example.com/1', similarSource: 'https://example.com/4' }
const row = (source=columns as readonly (readonly [string,string])[]) => source.map(([,field])=>data[field as keyof typeof data] || '')
const clipboard = (rows:string[][], html=false) => ({ getData: (type:string) => type==='text/html' ? html ? `<table>${rows.map(r=>`<tr>${r.map(c=>`<td>${c}</td>`).join('')}</tr>`).join('')}</table>` : '' : rows.map(r=>r.join('\t')).join('\r\n') })
const parse = (rows:string[][], html=false, sourceColumns=columns as readonly (readonly [string,string])[]) => readUnifiedPurchaseClipboardResult(clipboard(rows,html),0,{columns,sourceColumns}).rows

describe('optional special invoice procurement fields',()=>{
  it('accepts the exact 42-column attachment headers and ignores its optimization-price cell',()=>{
    const source=['','','',...row()];source[16]='888.88'
    const result=validatePurchasePaste(parse([attachmentHeaders,source]),true,columns)
    expect(result.issues).toEqual([])
    expect(result.records[0]).toMatchObject({purchasePriceCny:100,specialTaxIncludedPriceCny:113,specialTaxPoint:.13,specialInvoiceType:'专票13',category:'女装'})
    expect(result.records[0]).not.toHaveProperty('tier2MinQty')
    const sourceColumns=columns.map(column=>column[1]==='tier2MinQty'?['优化后价格','ignoredOptimizationPrice'] as const:column)
    expect(validatePurchasePaste(parse([source],false,sourceColumns),true,columns).records).toEqual(result.records)
  })
  it.each([false,true])('keeps separate prices, rates, category and links with HTML=%s',html=>{
    for(const images of [[],['','','']]) {
      const parsed=parse([[...images,...row()]],html)
      const checked=validatePurchasePaste(parsed,false,columns)
      expect(checked.issues).toEqual([])
      expect(checked.records[0]).toMatchObject({taxIncludedPriceCny:103,taxPoint:.03,invoiceType:'普票',specialTaxIncludedPriceCny:113,specialTaxPoint:.13,specialInvoiceType:'专票13',category:'女装',similarSource:'https://example.com/4'})
    }
  })
  it('maps old headers and explicitly selected old whole rows without shifting',()=>{
    for(const rows of [parse([oldColumns.map(c=>c[0]),row(oldColumns)]),parse([row(oldColumns)],false,oldColumns)]) {
      expect(validatePurchasePaste(rows,false,columns).records[0]).toMatchObject({specialTaxIncludedPriceCny:null,specialTaxPoint:null,specialInvoiceType:'',category:'女装',sourceLink1:'https://example.com/1'})
    }
    expect(()=>parse([row(oldColumns)])).toThrow('无表头')
  })
  it('permits each field to be blank independently and never turns blank into zero',()=>{
    for(const field of ['specialTaxIncludedPriceCny','specialTaxPoint','specialInvoiceType']) {
      const input=row(); input[columns.findIndex(c=>c[1]===field)]=''
      expect(validatePurchasePaste([input],false,columns).canSave).toBe(true)
      expect(validatePurchasePaste([input],true,columns).records[0]).not.toHaveProperty(field)
    }
    for(const rate of ['13%','13％','13','0.13','0%']) {
      const input=row();input[29]=rate
      expect(validatePurchasePaste([input],true,columns).records[0]?.specialTaxPoint).toBe(rate==='0%'?0:.13)
    }
  })
  it('blocks malformed fields and does not affect ordinary purchase pricing',()=>{
    for(const rate of ['-1','101%','abc']) { const input=row(); input[29]=rate; expect(validatePurchasePaste([input],true,columns).canSave).toBe(false) }
    expect(()=>validateSpecialInvoice({specialTaxPoint:1.01})).toThrow('专票点')
    expect(()=>validateSpecialInvoice({specialTaxIncludedPriceCny:-1})).toThrow('含专票价')
    const product=validatePurchasePaste([row()],false,columns).records[0]!
    const original=normalizePurchaseRecord({...product,specialTaxIncludedPriceCny:null,specialTaxPoint:null,specialInvoiceType:''})
    expect(purchasePriceBreakdown(product, 'tier1')).toEqual(purchasePriceBreakdown(original, 'tier1'))
    expect(normalizePurchaseRecord(product)).toMatchObject({specialTaxIncludedPriceCny:113,specialTaxPoint:.13,specialInvoiceType:'专票13'})
  })
})
