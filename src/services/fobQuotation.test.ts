import { beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from './http'
import { fobQuantityQuote, fobSheetRows, fobTierQuotes, fromStandardPurchase, loadFobQuoteProduct, type FobQuoteProduct } from './fobQuotation'
import { loadFobRecord } from './fobPurchase'
import { loadPurchaseProduct, normalizePurchaseRecord } from '@/data/purchaseStore'
vi.mock('./fobPurchase', () => ({ loadFobRecord: vi.fn() }))
vi.mock('@/data/purchaseStore', async original => ({ ...await original<typeof import('@/data/purchaseStore')>(), loadPurchaseProduct: vi.fn() }))
const product = (): FobQuoteProduct => ({ sku: 'KJ2600788', category: '', weight: '60', source: 'fob', notices: [], parsed: { minOrderQty: 10, orderMultiple: 1,
  priceTiers: [{ minQty: 10, maxQty: null, unitPriceCny: 7.2, unit: '件' }], freight: { quantity: 100, totalFreightCny: 0, unitFreightCny: 0, estimated: false, basis: '包邮' } } })
const standard = () => normalizePurchaseRecord({ sku: 'PF2600053', dataSource: 'standard', catalogState: 'ready', minOrderQty: 1, purchasePriceCny: 16, tier2MinQty: 100, tier2PriceCny: 13.8, tier3MinQty: 300, tier3PriceCny: 13, freight100Cny: 30, weightG: 80, taxPoint: .02 })
beforeEach(() => vi.clearAllMocks())
it('shows final customer prices on each side of the surcharge boundary, respecting finite ranges and multiples', () => {
  const p = product(), policy = { scope: 'single-price', calculation: 'before-coefficient' } as const
  expect(fobSheetRows(p,6.7,policy)).toEqual([
    {minQty:10,maxQty:25,unit:'件',declaredUsd:'1.69',undeclaredUsd:'1.72'},
    {minQty:26,maxQty:null,unit:'件',declaredUsd:'1.35',undeclaredUsd:'1.37'},
  ])
  p.parsed.orderMultiple=10;p.parsed.priceTiers[0]!.maxQty=39
  expect(fobSheetRows(p,6.7,policy).map(r=>[r.minQty,r.maxQty])).toEqual([[10,20],[30,30]])
  p.parsed.priceTiers[0]!.maxQty=20
  expect(fobSheetRows(p,6.7,policy)).toEqual([{minQty:10,maxQty:20,unit:'件',declaredUsd:'1.69',undeclaredUsd:'1.72'}])
  expect(()=>fobSheetRows(p,6.7,null)).toThrow('尚未确认')
})
it('matches the user single-price example with ceiling threshold and two quote types', () => {
  const p = product(), rows = fobTierQuotes(p, 6.7)
  expect(rows[0]).toMatchObject({ taxIncludedCny: 7.92, costCny: 7.92, declaredUsd: '1.35', undeclaredUsd: '1.37', thresholdQty: 26 })
  const policy = { scope: 'single-price', calculation: 'before-coefficient' } as const
  expect(fobQuantityQuote(p, rows, '26', 6.7, policy)).toMatchObject({ extraCny: 0, declaredUsd: '1.35' })
  expect(fobQuantityQuote(p, rows, '25', 6.7, policy)).toMatchObject({ extraCny: 2, declaredUsd: '1.69', undeclaredUsd: '1.72' })
  expect(fobQuantityQuote(p, rows, '25', 6.7, { ...policy, calculation: 'after-coefficient' }).declaredUsd).toBe('1.65')
  expect(() => fobQuantityQuote(p, rows, '9', 6.7, policy)).toThrow('起订量')
})
it('keeps every procurement tier and shares bulk freight, with no 0.05 rounding', () => {
  const p = product(); p.parsed.minOrderQty = 1
  p.parsed.priceTiers = [8.54,7.13,6.64].map((price,i) => ({ minQty: [1,200,210][i]!, maxQty: [199,209,null][i]!, unit: '件', unitPriceCny: price }))
  p.parsed.freight.unitFreightCny = .185
  const rows = fobTierQuotes(p,6.7)
  expect(rows.map(r => [r.declaredUsd,r.undeclaredUsd])).toEqual([['1.63','1.66'],['1.37','1.39'],['1.27','1.30']])
  expect(rows[0]?.costCny).toBe(9.579)
  expect(fobQuantityQuote(p,rows,'200',6.7,{scope:'single-price',calculation:'before-coefficient'}).row.purchaseCny).toBe(7.13)
  expect(fobQuantityQuote(p,rows,'1',6.7,{scope:'single-price',calculation:'before-coefficient'}).extraCny).toBe(0)
  expect(fobQuantityQuote(p,rows,'1',6.7,{scope:'all',calculation:'before-coefficient'}).extraCny).toBe(2)
  p.parsed.priceTiers = [16,13.8,13,13,12.8].map((v,i) => ({ minQty: [1,100,300,500,1000][i]!, maxQty:[99,299,499,999,null][i]!, unit:'件', unitPriceCny:v }))
  expect(fobTierQuotes(p,6.7)).toHaveLength(5)
})
it('enforces multiples, finite tier ranges, valid quantity and exchange rate', () => {
  const p = product(); p.parsed.orderMultiple = 10; p.parsed.priceTiers[0]!.maxQty = 100
  const rows = fobTierQuotes(p,6.7)
  expect(rows[0]?.thresholdQty).toBe(30)
  p.parsed.priceTiers[0]!.maxQty=20
  expect(fobTierQuotes(p,6.7)[0]?.thresholdQty).toBeNull()
  p.parsed.priceTiers[0]!.maxQty=100
  expect(() => fobQuantityQuote(p,rows,'26',6.7,null)).toThrow('倍数')
  expect(() => fobQuantityQuote(p,rows,'110',6.7,null)).toThrow('有效采购价')
  expect(() => fobQuantityQuote(p,rows,'20',6.7,null)).toThrow('尚未确认')
  for (const q of ['0','1.5','-1','NaN','1e2','9007199254740992']) expect(() => fobQuantityQuote(p,rows,q,6.7,null)).toThrow()
  for (const rate of [0,-1,Infinity,NaN]) expect(() => fobTierQuotes(p,rate)).toThrow('汇率')
})
it('does not charge the surcharge at exactly 200 and uses unrounded costs for the threshold', () => {
  const p=product(); p.parsed.minOrderQty=1; p.parsed.priceTiers[0]={minQty:1,maxQty:null,unitPriceCny:0,unit:'件'};p.parsed.freight.unitFreightCny=10
  let rows=fobTierQuotes(p,6.7)
  expect(fobQuantityQuote(p,rows,'20',6.7,{scope:'all',calculation:'before-coefficient'}).extraCny).toBe(0)
  p.parsed.freight.unitFreightCny=9.99999;rows=fobTierQuotes(p,6.7)
  expect(rows[0]?.thresholdQty).toBe(21)
  expect(fobQuantityQuote(p,rows,'20',6.7,{scope:'all',calculation:'before-coefficient'}).extraCny).toBe(2)
})
it('uses standard untaxed tiers and explicit hundred-piece freight without importing its tax point', () => {
  const p = fromStandardPurchase(standard()), rows = fobTierQuotes(p,6.7)
  expect(rows).toHaveLength(3); expect(rows[0]?.taxIncludedCny).toBe(17.6); expect(rows[0]?.freightCny).toBe(.3)
  expect(() => fromStandardPurchase({...standard(),freight100Cny:null,singleFreightCny:0})).toThrow('100件总运费')
  expect(fromStandardPurchase({...standard(),freeShipping:'是',freight100Cny:null}).parsed.freight.unitFreightCny).toBe(0)
  expect(() => fromStandardPurchase({...standard(),purchasePriceBasis:'tax_included'})).toThrow('含票价')
  expect(() => fromStandardPurchase({...standard(),catalogState:'disabled'})).toThrow('停用')
  expect(() => fromStandardPurchase({...standard(),dataSource:'legacy_2026'})).toThrow('旧采购')
})
it('prefers dedicated FOB data and only falls back on actual absence', async () => {
  vi.mocked(loadFobRecord).mockResolvedValue({sku:'KJ2600788',parsed:product().parsed})
  expect((await loadFobQuoteProduct('kj2600788','auto')).source).toBe('fob'); expect(loadPurchaseProduct).not.toHaveBeenCalled()
  vi.mocked(loadFobRecord).mockRejectedValue(new ApiError('未找到',404,'FOB_PURCHASE_NOT_FOUND','test'))
  vi.mocked(loadPurchaseProduct).mockResolvedValue(standard())
  expect((await loadFobQuoteProduct('PF2600053','auto')).source).toBe('standard')
  vi.mocked(loadPurchaseProduct).mockClear()
  for (const error of [new ApiError('权限',403,'FORBIDDEN','test'),new ApiError('接口未部署',404,'NOT_FOUND','test'),new Error('网络失败')]) {
    vi.mocked(loadFobRecord).mockRejectedValue(error)
    await expect(loadFobQuoteProduct('PF2600053','auto')).rejects.toThrow(); expect(loadPurchaseProduct).not.toHaveBeenCalled()
  }
})
