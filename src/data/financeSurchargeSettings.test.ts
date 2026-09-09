import { describe, expect, it, vi } from 'vitest'
vi.mock('@/services/financeSettings', () => ({ readFinanceSetting: vi.fn(), writeFinanceSetting: vi.fn() }))
import { calculateFinanceQuoteFees, normalizeFinanceSurchargeSettings, saveFinanceSurchargeSettings } from './financeSurchargeSettings'
import { type FinanceTaxSettings } from './financeTaxSettings'
import { writeFinanceSetting } from '@/services/financeSettings'

const settings = (amount: number, exempt = false): FinanceTaxSettings => ({
  countries: [{ country: '美国', fixedFeeUsd: amount, selected: true, enabled: true, sortOrder: 1 }],
  providers: [{ provider: '递四方', mode: exempt ? 'exempt' : 'taxable', selected: true, channels: [] }], updatedAt: '',
})
describe('independent provider surcharge', () => {
  it('rounds once after combining base price, tax and surcharge', () => {
    const result = calculateFinanceQuoteFees(settings(0.02), settings(0.02), '美国', '递四方', 6.01)
    expect(result.totalUsd).toBe(6.05)
    expect(result.taxUsd).toBe(0.02)
    expect(result.surchargeUsd).toBe(0.02)
    expect(calculateFinanceQuoteFees(settings(0), settings(0), '美国', '递四方', 6.01).totalUsd).toBe(6.05)
  })
  it.each([[false, false, 27], [true, false, 22], [false, true, 25], [true, true, 20]])('tax exempt %s, surcharge exempt %s gives %s', (taxExempt, surchargeExempt, total) => {
    expect(calculateFinanceQuoteFees(settings(5, taxExempt), settings(2, surchargeExempt), '美国', '递四方', 20).totalUsd).toBe(total)
  })
  it('does not charge unconfigured countries and blocks missing provider attributes only when a fee applies', () => {
    expect(calculateFinanceQuoteFees(settings(5), settings(2), '英国', '未知商', 20)).toMatchObject({ configured: true, totalUsd: 20, surchargeEnabled: false })
    expect(calculateFinanceQuoteFees(settings(0), settings(2), '美国', '未知商', 20)).toMatchObject({ configured: false, surchargeLabel: '附加费渠道配置待确认' })
    expect(calculateFinanceQuoteFees(settings(0), settings(2), '美国', '递四方', 20)).toMatchObject({ totalUsd: 22, feeMode: 'no-tax' })
  })
  it('starts independently without importing tax exemptions', () => {
    const defaults = normalizeFinanceSurchargeSettings()
    expect(defaults.countries.some(c => c.selected)).toBe(false)
    expect(defaults.providers.some(p => p.selected || p.mode === 'exempt')).toBe(false)
  })
  it('saves only the independent key and rejects invalid amounts', async () => {
    await saveFinanceSurchargeSettings(settings(2))
    expect(writeFinanceSetting).toHaveBeenCalledWith('surcharge-settings', expect.objectContaining({ countries: expect.arrayContaining([expect.objectContaining({ country: '美国', fixedFeeUsd: 2 })]) }))
    await expect(saveFinanceSurchargeSettings(settings(-1))).rejects.toThrow('非负')
    await expect(saveFinanceSurchargeSettings(settings(NaN))).rejects.toThrow('非负')
  })
})

it('isolates country and channel exemptions, including empty selection and missing channel', () => {
  const scoped = { ...settings(1.5, true), countries: [
    { ...settings(1.5).countries[0]!, country: '新西兰', exemptChannelKeys: ['1::递四方::A'] },
    { ...settings(0.5).countries[0]!, country: '英国', exemptChannelKeys: ['1::递四方::B'] },
    { ...settings(2).countries[0]!, country: '美国', exemptChannelKeys: [] },
  ] }
  const quote = (country: string, key: string) => calculateFinanceQuoteFees(settings(0), scoped, country, '递四方', 6.01, key)
  expect(quote('新西兰', '1::递四方::A').surchargeUsd).toBe(0)
  expect(quote('新西兰', '1::递四方::B').surchargeUsd).toBe(1.5)
  expect(quote('英国', '1::递四方::A').surchargeUsd).toBe(0.5)
  expect(quote('英国', '1::递四方::B').surchargeUsd).toBe(0)
  expect(quote('美国', '1::递四方::A').surchargeUsd).toBe(2)
  expect(quote('新西兰', '').configured).toBe(false)
  expect(quote('英国', '1::递四方::A').totalUsd).toBe(6.55)
  const normalized = normalizeFinanceSurchargeSettings(scoped)
  expect(normalized.countries.find(row => row.country === '美国')?.exemptChannelKeys).toEqual([])
  expect(normalized.countries.find(row => row.country === '新西兰')?.exemptChannelKeys).toEqual(['1::递四方::A'])
})

it('uses country providers for every channel and preserves independent settings on reload', async () => {
  const scoped = { ...settings(1.5), countries: [
    { ...settings(1.5).countries[0]!, country: '新西兰', providers: settings(1.5, true).providers },
    { ...settings(0.5).countries[0]!, country: '英国', providers: settings(0.5, false).providers },
  ] }
  for (const key of ['1::递四方::A', '2::递四方::B']) {
    expect(calculateFinanceQuoteFees(settings(0), scoped, '新西兰', '递四方', 10, key).surchargeUsd).toBe(0)
    expect(calculateFinanceQuoteFees(settings(0), scoped, '英国', '递四方', 10, key).surchargeUsd).toBe(0.5)
  }
  expect(calculateFinanceQuoteFees(settings(0), scoped, '英国', '未配置商', 10).configured).toBe(false)
  const saved = await saveFinanceSurchargeSettings(scoped)
  expect(saved.countries.find(c => c.country === '新西兰')?.providers?.[0]?.mode).toBe('exempt')
  expect(saved.countries.find(c => c.country === '英国')?.providers?.[0]?.mode).toBe('taxable')
})
