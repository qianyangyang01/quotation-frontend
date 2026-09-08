import { afterEach, expect, it, vi } from 'vitest'
import { calculateLogisticsFee, isPriceRowEligible, replaceLogisticsRules, type LogisticsRule } from './logistics'
import { financeAllowsLogisticsChannel, financeChannelKey, financeLogisticsAttributeOptions, loadFinanceChannelPolicies, saveFinanceChannelPolicies, type FinanceChannelPolicy } from './financeChannelPolicies'
import { readFinanceSetting, writeFinanceSetting } from '@/services/financeSettings'

vi.mock('@/services/financeSettings', () => ({ readFinanceSetting: vi.fn(), writeFinanceSetting: vi.fn() }))
afterEach(() => { replaceLogisticsRules([]); vi.clearAllMocks() })
const relation = { carrier: '测试物流', channel: '渠道', channelCode: 'C1', discounts: '' }
const rule = { id: 1, name: '渠道', status: '启用', relations: [relation], prices: [{ areaName: '美国', countryCode: 'US', weightFromKg: 0, weightToKg: 2, pricePerKg: 50, registrationFee: 10, allowedMarks: '', prohibitedMarks: '', quoteReady: true }] } as LogisticsRule

it('puts the four preferred attributes first for finance and quotation', () => {
  expect(financeLogisticsAttributeOptions.slice(0, 4)).toEqual(['普货', '化妆品', '保健品', '带电'])
})

it.each(['化妆品', '保健品'])('requires explicit finance authorization for %s and preserves it through saving', async attribute => {
  replaceLogisticsRules([rule])
  expect(financeLogisticsAttributeOptions).toContain(attribute)
  expect(financeLogisticsAttributeOptions).toContain('非液体化妆品')
  vi.mocked(readFinanceSetting).mockReturnValue(undefined)
  expect(loadFinanceChannelPolicies().some(p => p.category === attribute)).toBe(false)
  const key = financeChannelKey(1, relation)
  const policy: FinanceChannelPolicy = { id: attribute, category: attribute, enabled: true, updatedAt: 'test', countryRules: [{ country: '美国', allowedChannels: [key], stage: 'common', continent: '北美洲', sortOrder: 1 }] }
  expect(financeAllowsLogisticsChannel([{ ...policy, category: '普货' }], attribute, '美国', 1, relation)).toBe(false)
  expect(financeAllowsLogisticsChannel([{ ...policy, category: '非液体化妆品' }], attribute, '美国', 1, relation)).toBe(false)
  vi.mocked(writeFinanceSetting).mockImplementation(async (_key, value) => value)
  const saved = await saveFinanceChannelPolicies([policy])
  vi.mocked(readFinanceSetting).mockReturnValue(JSON.parse(JSON.stringify(saved)))
  const loaded = loadFinanceChannelPolicies()
  expect(loaded[0]?.category).toBe(attribute)
  expect(financeAllowsLogisticsChannel(loaded, attribute, '美国', 1, relation)).toBe(true)
  expect(financeAllowsLogisticsChannel(loaded, attribute, '加拿大', 1, relation)).toBe(false)
  expect(financeAllowsLogisticsChannel([{ ...policy, enabled: false }], attribute, '美国', 1, relation)).toBe(false)
  expect(calculateLogisticsFee(rule, '美国', .5, [attribute])?.total).toBe(35)
})

it.each(['化妆品', '保健品'])('matches explicit allowed and prohibited marks without aliases for %s', attribute => {
  const row = rule.prices[0]!
  expect(isPriceRowEligible({ ...row, allowedMarks: '非液体化妆品' }, [attribute])).toBe(false)
  expect(isPriceRowEligible({ ...row, allowedMarks: attribute }, [attribute])).toBe(true)
  expect(isPriceRowEligible({ ...row, prohibitedMarks: attribute }, [attribute])).toBe(false)
  expect(isPriceRowEligible({ ...row, allowedMarks: '普货' }, [attribute])).toBe(false)
})
