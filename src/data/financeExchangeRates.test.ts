import { beforeEach, expect, it, vi } from 'vitest'
import { loadFinanceExchangeRate, saveFinanceExchangeRate, saveFinanceEurUsdRate } from './financeChannelPolicies'
const store = vi.hoisted(() => ({ value: {} as Record<string, unknown>, write: vi.fn() }))
vi.mock('@/services/financeSettings', () => ({ readFinanceSetting: () => store.value, writeFinanceSetting: async (key: string, value: Record<string,unknown>) => { store.write(key,value); store.value = value } }))
beforeEach(() => { store.value={usdCny:6.7,updatedAt:'before'}; store.write.mockClear() })
it('does not invent a euro rate for historical settings', () => { expect(loadFinanceExchangeRate().eurUsd).toBeUndefined() })
it('saves each rate without discarding the other rate, including reload', async () => {
  await saveFinanceEurUsdRate(1.23456)
  expect(loadFinanceExchangeRate()).toMatchObject({usdCny:6.7,eurUsd:1.23456})
  await saveFinanceExchangeRate(7.1)
  expect(loadFinanceExchangeRate()).toMatchObject({usdCny:7.1,eurUsd:1.23456})
})
it('rejects invalid euro rates before any write', async () => {
  for(const rate of [0,-1,NaN,Infinity]) await expect(saveFinanceEurUsdRate(rate)).rejects.toThrow('必须大于 0')
  expect(store.write).not.toHaveBeenCalled()
})
