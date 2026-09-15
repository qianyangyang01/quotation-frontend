import { describe, expect, it } from 'vitest'
import { EU_MEMBER_STATES, EU_TAX_GROUP, isEuCountry } from './europeanUnion'
import { calculateFinanceQuoteTax, normalizeFinanceTaxSettings } from './financeTaxSettings'
import { calculateFinanceQuoteFees, normalizeFinanceSurchargeSettings } from './financeSurchargeSettings'
import { EU_YUNEXPRESS_CHC_CHANNEL_CODES } from './euYunExpressTax'

const provider = { provider: '云途', selected: true, mode: 'taxable' as const, channels: [] }
const group = { country: EU_TAX_GROUP, selected: true, enabled: true, fixedFeeUsd: 3.52, sortOrder: 1, providers: [provider] }
const settings = () => normalizeFinanceTaxSettings({ countries: [group], providers: [] })

describe('EU grouped customs duty', () => {
  it('provides a single inactive group without changing existing or surcharge settings', () => {
    const empty = normalizeFinanceTaxSettings()
    expect(empty.countries.find(row => row.country === EU_TAX_GROUP)).toMatchObject({ selected: false, enabled: false, fixedFeeUsd: 0 })
    expect(normalizeFinanceSurchargeSettings().countries.some(row => row.country === EU_TAX_GROUP)).toBe(false)
  })

  it('covers all 27 members in Chinese, English and country codes, including case and whitespace', () => {
    expect(EU_MEMBER_STATES).toHaveLength(27)
    for (const row of EU_MEMBER_STATES) for (const alias of row) {
      expect(isEuCountry(alias)).toBe(true)
      expect(calculateFinanceQuoteTax(settings(), ` ${alias.toLowerCase()} `, '云途', 10)).toMatchObject({ configured: true, taxUsd: 3.52, totalUsd: 13.52 })
    }
    for (const country of ['英国', 'GB', 'UK', '瑞士', 'CH', '挪威', 'NO', '冰岛', 'IS', 'US', '法国海外地区'])
      expect(calculateFinanceQuoteTax(settings(), country, '云途', 10)).toMatchObject({ taxUsd: 0, feeMode: 'no-tax' })
  })

  it('keeps explicitly selected member-country overrides, including zero duty, independent of ordering', () => {
    const individual = { ...group, country: '法国', fixedFeeUsd: 1.2, providers: [{ ...provider, mode: 'exempt' as const }] }
    for (const countries of [[group, individual], [individual, group]]) {
      const tax = normalizeFinanceTaxSettings({ countries })
      expect(calculateFinanceQuoteTax(tax, 'FR', '云途', 10).feeMode).toBe('exempt')
      expect(calculateFinanceQuoteTax(tax, '德国', '云途', 10).taxUsd).toBe(3.52)
      const france = tax.countries.find(row => row.country === '法国')!
      france.fixedFeeUsd = 0
      expect(calculateFinanceQuoteTax(tax, 'France', '未配置物流商', 10).feeMode).toBe('no-tax')
      france.selected = false
      expect(calculateFinanceQuoteTax(tax, '法国', '云途', 10).taxUsd).toBe(3.52)
    }
  })

  it('supports exemptions, missing provider protection, edits, deletion and JSON round trips', () => {
    const original = settings()
    const reloaded = normalizeFinanceTaxSettings(JSON.parse(JSON.stringify(original)))
    expect(calculateFinanceQuoteTax(reloaded, '德国', '其他物流商', 10).configured).toBe(false)
    const eu = reloaded.countries.find(row => row.country === EU_TAX_GROUP)!
    eu.providers![0]!.mode = 'exempt'
    expect(calculateFinanceQuoteTax(reloaded, '德国', '云途', 10).feeMode).toBe('exempt')
    expect(calculateFinanceQuoteTax(original, '德国', '云途', 10).taxUsd).toBe(3.52)
    eu.providers![0]!.mode = 'taxable'
    eu.fixedFeeUsd = 4
    expect(calculateFinanceQuoteTax(reloaded, '德国', '云途', 10).taxUsd).toBe(4)
    eu.selected = false
    expect(calculateFinanceQuoteTax(reloaded, '德国', '云途', 10).taxUsd).toBe(0)
  })

  it('retains all three CHC weight rules ahead of EU fixed tax, including missing exchange protection', () => {
    for (const code of EU_YUNEXPRESS_CHC_CHANNEL_CODES) {
      const context = { channelKey: `1::云途::${code}`, weightKg: 0.755, eurUsd: 1.16 }
      expect(calculateFinanceQuoteTax(settings(), '德国', '云途', 10, context)).toMatchObject({ taxUsd: 2.01, totalUsd: 12.01, fixedFeeUsd: 0, feeMode: 'weight-eur' })
      expect(calculateFinanceQuoteTax(settings(), '德国', '云途', 10, { ...context, eurUsd: undefined }).configured).toBe(false)
    }
  })

  it('keeps surcharge country matching independent and charges EU duty only once across quantity totals', () => {
    const surcharge = normalizeFinanceSurchargeSettings({ countries: [{ ...group, country: '德国', fixedFeeUsd: 0.5 }] })
    for (const base of [10, 20, 30, 80, 100]) {
      expect(calculateFinanceQuoteFees(settings(), surcharge, '德国', '云途', base)).toMatchObject({ taxUsd: 3.52, surchargeUsd: 0.5 })
      expect(calculateFinanceQuoteFees(settings(), surcharge, '法国', '云途', base)).toMatchObject({ taxUsd: 3.52, surchargeUsd: 0 })
      expect(calculateFinanceQuoteFees(settings(), settings(), '德国', '云途', base).surchargeUsd).toBe(0)
    }
  })
})
