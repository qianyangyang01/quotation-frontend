import { describe, expect, it } from 'vitest'
import { formatLogisticsEta } from './logistics'
import {
  buildCustomerQuoteSheet, CUSTOMER_QUOTE_NOTES, formatQuoteDate, formatShippingTime,
  localQuoteDate, newQuoteSheetEdits, quoteSheetCountryCode, quoteSheetProviderName,
  quoteSheetRowKey, quoteSheetProviderKey, customerQuoteSheetTsv, reconcileQuoteSheetEdits, type QuoteSheetSourceRow,
} from './customerQuoteSheet'

function source(overrides: Partial<QuoteSheetSourceRow> = {}): QuoteSheetSourceRow {
  return {
    country: '美国', carrier: '燕文', transport: '专线', channelKey: 'channel-1', ruleId: 1,
    rule: '规则', channelCode: 'YW-1', eta: '6～12 天',
    quote1: 12.8, quote2: 20.5, quote3: 28.2, quoteCustom: 43.6, ...overrides,
  }
}
const edits = () => newQuoteSheetEdits('Alex', new Date(2026, 8, 12))

describe('customer quotation presentation', () => {
  it('treats the real logistics missing/conflicting ETA placeholder as absent without altering source data', () => {
    const eta = formatLogisticsEta({ etaMinDays: 0, etaMaxDays: 0 })
    const row = Object.freeze(source({ carrier: '极通环球', eta }))
    const sheet = buildCustomerQuoteSheet({ rows: [row], countries: [], edits: edits(), customQuantity: 5, bundle: false })
    expect(sheet.issues).toEqual([])
    expect(sheet.rows[0].shippingTime).toBe('—')
    expect(customerQuoteSheetTsv(sheet)).toContain('JITO\t—\t1-2 days')
    expect(row.eta).toBe('该物流暂无时效说明')
    expect(formatShippingTime(formatLogisticsEta({ etaMinDays: 7, etaMaxDays: 15, etaStatus: 'conflict' }))).toBe('—')
    expect(formatShippingTime('旺季可能延误')).toBe('旺季可能延误')
  })
  it('covers all 14 production provider names, including the failing screenshot routes', () => {
    const names = ['万邦', '云途', '云速递', '容鼎', '捷易通达', '极通环球', '燕文', '百洲', '花海', '递四方', '通邮', '闪电猴', '顺丰', '顺友']
    const expected = ['Wanb Express', 'YunExpress', 'SFYD Express', 'Rongding', 'JYTD', 'JITO', 'Yanwen', 'Baizhou', 'Hua Hai', '4PX', 'TopYou', 'SDH Express', 'SF Express', 'SunYou']
    const sheet = buildCustomerQuoteSheet({ rows: names.map((carrier, i) => source({ carrier, channelKey: String(i) })), countries: [], edits: edits(), customQuantity: 5, bundle: false })
    expect(sheet.issues).toEqual([])
    expect(sheet.rows.map(row => row.provider)).toEqual(expected)
    expect(customerQuoteSheetTsv(sheet).split('\r\n')).toHaveLength(15)
    expect(quoteSheetProviderName(' ４ＰＸ ')).toBe('4PX')
  })

  it('shares temporary English names across a provider while retaining every route and pruning removed names', () => {
    const one = source({ carrier: '新物流', channelKey: 'one' })
    const two = source({ carrier: '新物流', channelKey: 'two' })
    const draft = edits()
    const input = { rows: [one, two], countries: [], edits: draft, customQuantity: 5, bundle: false }
    expect(buildCustomerQuoteSheet(input).issues).toHaveLength(1)
    draft.providerNames![quoteSheetProviderKey(one.carrier)] = 'New Logistics'
    Object.freeze(one); Object.freeze(two)
    expect(buildCustomerQuoteSheet(input).rows.map(row => row.provider)).toEqual(['New Logistics', 'New Logistics'])
    expect(buildCustomerQuoteSheet(input).issues).toEqual([])
    expect(reconcileQuoteSheetEdits(draft, [two]).providerNames).toEqual(draft.providerNames)
    expect(reconcileQuoteSheetEdits(draft, [source()]).providerNames).toEqual({})
    expect(newQuoteSheetEdits('Alex').providerNames).toEqual({})
    draft.providerNames![quoteSheetProviderKey(one.carrier)] = '仍然中文'
    expect(() => customerQuoteSheetTsv(buildCustomerQuoteSheet(input))).toThrow('英文名称')
    expect(one.carrier).toBe('新物流')
  })

  it('requires agent/date for the image but not for the table-only clipboard', () => {
    const draft = edits(); draft.agent = ''; draft.date = '2026-02-30'
    const sheet = buildCustomerQuoteSheet({ rows: [source()], countries: [], edits: draft, customQuantity: 5, bundle: false })
    expect(sheet.issues).toHaveLength(2)
    expect(sheet.tableIssues).toEqual([])
    expect(customerQuoteSheetTsv(sheet)).toContain('US\tYanwen')
    const legacy = { ...sheet, tableIssues: undefined }
    expect(() => customerQuoteSheetTsv(legacy)).toThrow('署名')
  })
  it('maps countries without changing the backend GB code or merging routes', () => {
    const countries = [{ name: '英国', code: 'GB' }, { name: '意大利', code: 'IT' }]
    expect(quoteSheetCountryCode('英国', countries)).toBe('UK')
    expect(quoteSheetCountryCode('gb', countries)).toBe('UK')
    expect(quoteSheetCountryCode('意大利', countries)).toBe('IT')
    expect(countries[0].code).toBe('GB')
    expect(['美国','英国','德国','法国','加拿大','澳大利亚'].map(name => quoteSheetCountryCode(name, [])))
      .toEqual(['US','UK','DE','FR','CA','AU'])
    const sheet = buildCustomerQuoteSheet({ rows: [source(), source({ channelKey: 'channel-2' })], countries, edits: edits(), customQuantity: 5, bundle: false })
    expect(sheet.rows).toHaveLength(2)
    expect(sheet.rows[0].key).not.toBe(sheet.rows[1].key)
  })

  it('uses existing USD quotes, preserves null prices, and supports custom and bundle quantities', () => {
    const row = source({ quote2: null, quote3: 0, quoteCustom: 999.99 })
    Object.freeze(row)
    const sheet = buildCustomerQuoteSheet({ rows: [row], countries: [], edits: edits(), customQuantity: 12, bundle: true })
    expect(sheet.quantityLabels).toEqual(['1 set', '2 sets', '3 sets', '12 sets'])
    expect(sheet.rows[0].prices).toEqual([12.8, null, 0, 999.99])
    expect(sheet.rows[0].shippingTime).toBe('6-12 days')
    expect(row.eta).toBe('6～12 天')
    expect(sheet.date).toBe('12 Sep 2026')
    expect(sheet.issues).toEqual([])
  })

  it('binds manual times to channel and region identities rather than row positions', () => {
    const one = source({ country: '澳大利亚', quoteRegion: '澳大利亚1区' })
    const two = source({ country: '澳大利亚', quoteRegion: '澳大利亚2区' })
    const draft = edits()
    draft.shippingTimes[quoteSheetRowKey(one)] = '8-15 days'
    const original = JSON.stringify([one, two])
    const sheet = buildCustomerQuoteSheet({ rows: [two, one], countries: [], edits: draft, customQuantity: 5, bundle: false })
    expect(sheet.rows.map(row => row.shippingTime)).toEqual(['6-12 days', '8-15 days'])
    expect(JSON.stringify([one, two])).toBe(original)
    expect(reconcileQuoteSheetEdits(draft, [two]).shippingTimes).toEqual({})
    expect(newQuoteSheetEdits('Other agent').shippingTimes).toEqual({})
  })

  it('does not invent missing delivery times and preserves explicit empty overrides', () => {
    for (const value of ['', '—', '-～- 天', '-～12 天', '6～- 天', '0～0 天']) expect(formatShippingTime(value)).toBe('—')
    expect(formatShippingTime('6～12 个工作日')).toBe('6-12 working days')
    const row = source()
    const draft = edits()
    draft.shippingTimes[quoteSheetRowKey(row)] = ''
    expect(buildCustomerQuoteSheet({ rows: [row], countries: [], edits: draft, customQuantity: 5, bundle: false }).rows[0].shippingTime).toBe('—')
  })

  it('reports unmapped identities instead of translating them into an unrelated provider/country', () => {
    expect(['燕文','递四方','云途','顺丰'].map(quoteSheetProviderName)).toEqual(['Yanwen','4PX','YunExpress','SF Express'])
    const sheet = buildCustomerQuoteSheet({ rows: [source({ country: '未知国家', carrier: '未配置物流商' })], countries: [], edits: edits(), customQuantity: 5, bundle: false })
    expect(sheet.issues).toHaveLength(2)
    expect(sheet.rows[0].country).toBe('—')
    expect(sheet.rows[0].provider).toBe('—')
  })

  it('uses local dates without UTC drift and rejects impossible calendar dates', () => {
    expect(localQuoteDate(new Date(2026, 8, 12, 0, 1))).toBe('2026-09-12')
    expect(formatQuoteDate('2026-02-29')).toBe('')
    expect(formatQuoteDate('2028-02-29')).toBe('29 Feb 2028')
    expect(formatQuoteDate('2026-13-01')).toBe('')
    expect(formatQuoteDate('2026-09-31')).toBe('')
  })

  it('keeps exactly the four approved terms and no added payment fees or sample disclaimer', () => {
    expect(CUSTOMER_QUOTE_NOTES).toHaveLength(4)
    expect(CUSTOMER_QUOTE_NOTES[1]).toBe('Payment methods: PayPal, Payoneer, Bank transfer, etc.')
    expect(CUSTOMER_QUOTE_NOTES.join('\n')).not.toMatch(/4%|100% payment|SAMPLE DATA/)
    expect(CUSTOMER_QUOTE_NOTES[3]).toContain('every single orders cannot be 100% guaranteed.')
  })
})


describe('editable quantity quote sheet boundaries', () => {
  it('supports ten arbitrary quantities, zero/missing USD and the bundle unit without mutating business rows', () => {
    const row = Object.freeze(source({ quote2: 0, quote3: null }))
    const quantities = [1,2,3,4,5,6,7,8,20,50]
    const sheet = buildCustomerQuoteSheet({rows:[row],countries:[],edits:edits(),customQuantity:5,bundle:true,quantities,calculatePrice:(_row,q)=>q+0.25})
    expect(sheet.issues).toEqual([])
    expect(sheet.quantityLabels).toEqual(quantities.map(q=>`${q} ${q===1?'set':'sets'}`))
    expect(sheet.rows[0].prices).toEqual([12.8,0,null,4.25,43.6,6.25,7.25,8.25,20.25,50.25])
    expect(customerQuoteSheetTsv(sheet).split('\r\n')[1].split('\t')).toHaveLength(15)
    expect(row.quoteCustom).toBe(43.6)
  })
  it.each([[],[0],[-1],[1.1],[NaN],[Infinity],[Number.MAX_SAFE_INTEGER+1],Array.from({length:11},(_,i)=>i+1),[1,1]].map(quantities => ({ quantities })))('blocks invalid columns $quantities before export', ({ quantities }) => {
    const sheet = buildCustomerQuoteSheet({rows:[source()],countries:[],edits:edits(),customQuantity:5,bundle:false,quantities})
    expect(sheet.issues.length).toBeGreaterThan(0)
    expect(()=>customerQuoteSheetTsv(sheet)).toThrow()
  })
  it('reports calculator failures instead of exporting stale prices or breaking the editor', () => {
    const sheet = buildCustomerQuoteSheet({rows:[source()],countries:[],edits:edits(),customQuantity:5,bundle:false,quantities:[8],calculatePrice:()=>{throw new Error('unavailable')}})
    expect(sheet.rows[0].prices).toEqual([null]); expect(sheet.issues[0]).toContain('计算失败')
  })
  it.each(['-1','1.001','1e4','=SUM(A1:A2)','Infinity','999999999999'])('rejects unsafe/invalid manual USD %s', price => {
    const row = source(); const draft=edits(); draft.fields={[quoteSheetRowKey(row)]:{prices:{'1':price}}}
    const sheet=buildCustomerQuoteSheet({rows:[row],countries:[],edits:draft,customQuantity:5,bundle:false})
    expect(()=>customerQuoteSheetTsv(sheet)).toThrow('美元金额')
  })
  it('keeps all four notes and uses local title/processing edits with formula-safe TSV', () => {
    const row=source();const draft=edits();draft.title='Custom Quote';draft.notes=[...CUSTOMER_QUOTE_NOTES];draft.notes[1]='Payment by bank transfer.'
    draft.fields={[quoteSheetRowKey(row)]:{provider:'=Custom',processingTime:'3-4 days',number:'8',prices:{'2':''}}}
    const sheet=buildCustomerQuoteSheet({rows:[row],countries:[],edits:draft,customQuantity:5,bundle:false})
    expect(sheet.title).toBe('Custom Quote');expect(sheet.notes).toHaveLength(4)
    expect(customerQuoteSheetTsv(sheet)).toContain("8\tUS\t'=Custom\t6-12 days\t3-4 days\t$12.80\t—")
    expect(CUSTOMER_QUOTE_NOTES[1]).toContain('PayPal')
  })
})
