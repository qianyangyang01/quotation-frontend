import { expect, it } from 'vitest'
import { buildCustomerQuoteSheet, newQuoteSheetEdits, customerQuoteSheetHtml, customerQuoteSheetTsv, quoteSheetTextTable } from './customerQuoteSheet'
import { normalizeCustomerPrices, recordCustomerPrices } from './customerQuotePrices'
import { normalizeQuotationRecord } from './quotationRecords'
const source = { country:'US', carrier:'4PX', channelCode:'', ruleId:1, rule:'', transport:'', eta:'5-8 days', quote1:1, quote2:2, quote3:3, quoteCustom:4 }
it('copies exactly one escaped merged cell and excludes it when disabled', () => {
 const edits = { ...newQuoteSheetEdits('QA'), sizeRules:'=bad<script>\nS & M', sizeRulesEnabled:true }
 const build = () => buildCustomerQuoteSheet({ rows:[{...source,channelKey:'a'},{...source,channelKey:'b'}],countries:[],edits,customQuantity:4,bundle:false })
 const sheet=build(), html=customerQuoteSheetHtml(sheet), table=quoteSheetTextTable(sheet)
 expect(html).toContain('rowspan="2"');expect(html).toContain("'=bad&lt;script&gt;<br>S &amp; M")
 expect(html.match(/rowspan=/g)).toHaveLength(1)
 const index=table[0].indexOf('Size Rules');expect(table[1][index]).toBe(edits.sizeRules);expect(table[2][index]).toBe('')
 expect(customerQuoteSheetTsv(sheet)).toContain("'=bad<script> S & M")
 edits.sizeRulesEnabled=false;expect(customerQuoteSheetHtml(build())).not.toContain('Size Rules')
})
it('round trips the notes and disabled switch without altering prices and enforces the length limit', () => {
 const raw={quantities:[1],rows:[{optionId:'a',prices:[2]}],sizeRules:'中'.repeat(2000),sizeRulesEnabled:false}
 expect(normalizeCustomerPrices(JSON.parse(JSON.stringify(raw)))).toEqual(raw)
 expect(normalizeCustomerPrices({...raw,sizeRules:'x'.repeat(2001)})).toBeUndefined()
 expect(normalizeCustomerPrices({...raw,sizeRulesEnabled:'true'})).toBeUndefined()
 const record=normalizeQuotationRecord({id:'r',no:'QT-r',salespersonAccount:'QA',customerQuote:raw,quoteOptions:[{id:'a',country:'US',carrier:'4PX',channel:'A',rule:'',eta:'',quote1Usd:2,quote2Usd:null,quote3Usd:null,quoteCustomUsd:null}]})!
 expect(recordCustomerPrices(record)).toMatchObject(raw)
})
