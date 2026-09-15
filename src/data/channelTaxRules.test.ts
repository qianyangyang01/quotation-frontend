import { describe, expect, it } from 'vitest'
import { calculateFinanceQuoteTax, normalizeFinanceTaxSettings } from './financeTaxSettings'
import { calculateFinanceQuoteFees, normalizeFinanceSurchargeSettings } from './financeSurchargeSettings'
import { channelTaxAmount, type ChannelTaxRule } from './channelTaxRules'
import { applyTaxWorkbookRows } from './channelTaxWorkbook'
const key = '1::燕文::C-test'
const rule: ChannelTaxRule = { key, mode: 'fixed-order', amount: 0.3, perKg: 0, currency: 'USD' }
function settings(overrides: Partial<ChannelTaxRule> = {}, country = '美国') { return normalizeFinanceTaxSettings({ countries: [{ country, fixedFeeUsd: 8, selected: true, enabled: true, sortOrder: 1, channelRules: [{ ...rule, ...overrides }] }], providers: [] }) }
describe('channel duty', () => {
  it('maps the six US channels to system-owned Flash Monkey without moving Yun Express channels', () => {
    const names = ['带电','敏感','普货'].flatMap(name => [`全球专线${name}-美国`, `全球专线${name}-美国（偏远）`])
    const catalog = names.map((channel, index) => ({ ruleId:index+1, carrier:'闪电猴', channel, channelCode:`C-us-${index}`, ruleName:channel, discounts:'' }))
    catalog.push({ruleId:10,carrier:'云速递',channel:'全球专线带电',channelCode:'C-global',ruleName:'全球专线带电',discounts:''})
    const before = JSON.stringify(catalog)
    const rows = [...names, '全球专线带电'].map((channel,index) => ({provider:'云速递', channel, row:index+2, values:{美国关税:'含'}}))
    const result = applyTaxWorkbookRows(rows,normalizeFinanceTaxSettings(),catalog)
    const rules = result.settings.countries.find(row => row.country === '美国')!.channelRules!
    expect(result.unmatched).toEqual([])
    expect(rules.filter(row => row.key.includes('::闪电猴::'))).toHaveLength(6)
    expect(rules.filter(row => row.key.includes('::云速递::'))).toHaveLength(1)
    expect(JSON.stringify(catalog)).toBe(before)
  })
  it('charges a flat USD fee only once for single/bundle quantity totals and survives serialization', () => {
    for (const count of [1,2,3,5,10]) for (const unit of ['件','套'] as const) {
      const value = normalizeFinanceTaxSettings(JSON.parse(JSON.stringify(settings())))
      expect(calculateFinanceQuoteTax(value, 'US', '燕文', count * 10, { channelKey: key, quantity: count, unit })).toMatchObject({ taxUsd: 0.3, totalUsd: count * 10 + 0.3, fixedFeeUsd: 0.3, configured: true })
    }
  })
  it('distinguishes no fee, included and unsupported, without blocking other countries', () => {
    expect(calculateFinanceQuoteTax(settings({ mode: 'no-tax' }), '美国','燕文',10,{channelKey:key})).toMatchObject({ taxUsd:0, included:false, configured:true })
    expect(calculateFinanceQuoteTax(settings({ mode: 'exempt' }), '美国','燕文',10,{channelKey:key})).toMatchObject({ taxUsd:0, included:true, configured:true })
    expect(calculateFinanceQuoteTax(settings({ mode: 'unavailable' }), '美国','燕文',10,{channelKey:key})).toMatchObject({ configured:false, label:'该渠道不支持当前国家' })
    expect(calculateFinanceQuoteTax(settings({ mode: 'unavailable' }), '英国','燕文',10,{channelKey:key}).configured).toBe(true)
  })
  it('retains EUR weight rules, zero overrides and separate surcharges', () => {
    const eu = settings({ mode:'weight', amount:0.6, perKg:1.5, currency:'EUR' }, '欧盟')
    expect(calculateFinanceQuoteTax(eu,'德国','燕文',10,{channelKey:key,weightKg:0.755,eurUsd:1.16})).toMatchObject({ taxUsd:2.01, totalUsd:12.01 })
    expect(calculateFinanceQuoteTax(eu,'德国','燕文',10,{channelKey:key,weightKg:0.755}).configured).toBe(false)
    expect(calculateFinanceQuoteTax(settings({amount:0}),'美国','燕文',10,{channelKey:key}).taxUsd).toBe(0)
    const surcharge=normalizeFinanceSurchargeSettings({ countries:[{country:'美国',selected:true,enabled:true,sortOrder:1,fixedFeeUsd:0.5,providers:[{provider:'燕文',selected:true,mode:'taxable',channels:[]}]}] })
    expect(calculateFinanceQuoteFees(settings(),surcharge,'美国','燕文',10,key)).toMatchObject({taxUsd:0.3,surchargeUsd:0.5,totalUsd:10.8})
  })
  it('validates numeric inputs and uses current exchange without changing old snapshots', () => {
    for (const amount of [NaN, Infinity,-1,1_000_001]) expect(()=>channelTaxAmount({...rule,amount},{})).toThrow()
    expect(channelTaxAmount({...rule,amount:23.05,currency:'CNY'},{usdCny:6.7})).toBe(3.44)
    const value=settings({amount:1,currency:'EUR'}), first=calculateFinanceQuoteTax(value,'美国','燕文',10,{channelKey:key,eurUsd:1.16})
    expect(calculateFinanceQuoteTax(value,'美国','燕文',10,{channelKey:key,eurUsd:1.2}).taxUsd).toBe(1.2)
    expect(first.calculation?.taxUsd).toBe(1.16)
  })
  it('imports dollar values directly and isolates remote/handling columns and unmatched channels', () => {
    const catalog=[{ruleId:1,carrier:'燕文',channel:'渠道A',channelCode:'C-test',ruleName:'A',discounts:''}]
    const result=applyTaxWorkbookRows([{provider:'燕文',channel:'渠道A',row:2,values:{欧盟关税:'含',美国关税:'无',新西兰:'不发',墨西哥关税:3.44029850746269,'新西兰-偏远':0.5,'罗马尼亚-关税处理费':6.12}},{provider:'未知',channel:'未知',row:3,values:{美国关税:99}}],normalizeFinanceTaxSettings(),catalog)
    expect(result.count).toBe(4); expect(result.unmatched).toEqual(['未知 / 未知'])
    const c=(country:string)=>result.settings.countries.find(row=>row.country===country)!.channelRules![0]!
    expect(c('美国').mode).toBe('no-tax');expect(c('新西兰').mode).toBe('unavailable');expect(c('欧盟').mode).toBe('exempt');expect(c('墨西哥')).toMatchObject({amount:3.44029850746269,currency:'USD'})
    expect(result.settings.countries.some(row=>row.country==='罗马尼亚'&&row.channelRules?.length)).toBe(false)
  })
})
