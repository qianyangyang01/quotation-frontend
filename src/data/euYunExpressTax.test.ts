import { describe, expect, it } from 'vitest'
import { calculateEuYunExpressTax, EU_YUNEXPRESS_CHC_CHANNEL_CODES, matchesEuYunExpressTax } from './euYunExpressTax'
import { calculateFinanceQuoteFees } from './financeSurchargeSettings'
import type { FinanceTaxSettings } from './financeTaxSettings'

const key = `593::云途::${EU_YUNEXPRESS_CHC_CHANNEL_CODES[1]}`
const tax = (weightKg: number, eurUsd = 1.2) => calculateEuYunExpressTax('德国', '云途', 10, { channelKey: key, weightKg, eurUsd })!
describe('EU YunExpress CHC weight tax', () => {
  it('matches all 27 EU countries only on the three exact channels', () => {
    const codes = 'AT BE BG HR CY CZ DK EE FI FR DE GR HU IE IT LV LT LU MT NL PL PT RO SK SI ES SE'.split(' ')
    expect(codes).toHaveLength(27)
    for (const country of codes) for (const code of EU_YUNEXPRESS_CHC_CHANNEL_CODES) expect(matchesEuYunExpressTax(country, '云途', `593::云途::${code}`)).toBe(true)
    for (const country of ['GB', '英国', 'UK', 'NO', 'CH', 'US', 'Turkey', '欧洲', '']) expect(matchesEuYunExpressTax(country, '云途', key)).toBe(false)
    for (const country of ['德国', 'Germany', 'france', '爱尔兰']) expect(matchesEuYunExpressTax(country, 'YunExpress', key)).toBe(true)
    expect(matchesEuYunExpressTax('DE', '云速递', key)).toBe(false)
    expect(matchesEuYunExpressTax('DE', '云途', '云途欧洲专线-CHC')).toBe(false)
    expect(matchesEuYunExpressTax('DE', '云途', key + '-other')).toBe(false)
    expect(matchesEuYunExpressTax('DE', '云途', key.replace('::云途::', '::燕文::'))).toBe(false)
  })
  it('uses kg including packaging and charges the fixed 0.6 only once per order', () => {
    expect(tax(0.1)).toMatchObject({ taxUsd: 0.9, totalUsd: 10.9, calculation: { taxEur: 0.75, weightKg: 0.1, eurUsd: 1.2 } })
    expect(tax(0.104).calculation?.taxEur).toBe(0.756)
    expect(tax(0.208)).toMatchObject({ taxUsd: 1.09, calculation: { taxEur: 0.912 } })
    expect(tax(1)).toMatchObject({ taxUsd: 2.52, calculation: { taxEur: 2.1 } })
    expect(tax(0.1, 1.1).taxUsd).toBe(0.83)
    expect(tax(0.1, 1.2).taxUsd).toBe(0.9)
  })
  it('blocks invalid weight and exchange rates without assigning a guessed rate', () => {
    for (const value of [undefined, 0, -1, NaN, Infinity]) {
      expect(calculateEuYunExpressTax('DE', '云途', 10, {channelKey:key,weightKg:0.1,eurUsd:value})).toMatchObject({ configured:false, feeMode:'missing', label:'请财务设置欧元兑美元汇率' })
      expect(calculateEuYunExpressTax('DE', '云途', 10, {channelKey:key,weightKg:value,eurUsd:1.2})?.configured).toBe(false)
    }
  })
  it('replaces the default tax, preserves separate surcharges, and rounds the final quote once', () => {
    const settings: FinanceTaxSettings = { countries:[{country:'德国',selected:true,enabled:true,fixedFeeUsd:8,sortOrder:1}],providers:[{provider:'云途',selected:true,mode:'exempt',channels:[]}],updatedAt:'' }
    const fees: FinanceTaxSettings = {...settings,countries:[{...settings.countries[0]!,fixedFeeUsd:0.5}],providers:[{...settings.providers[0]!,mode:'taxable'}]}
    expect(calculateFinanceQuoteFees(settings,fees,'德国','云途',10.01,key,{weightKg:0.1,eurUsd:1.2})).toMatchObject({taxUsd:0.9,surchargeUsd:0.5,totalUsd:11.45,feeMode:'weight-eur'})
    expect(calculateFinanceQuoteFees(settings,fees,'德国','云途',10.01,'other',{weightKg:0.1,eurUsd:1.2})).toMatchObject({taxUsd:0,totalUsd:10.55,feeMode:'exempt'})
    expect(calculateFinanceQuoteFees(settings,fees,'英国','云途',10.01,key)).toMatchObject({taxUsd:0,totalUsd:10.05,feeMode:'no-tax'})
  })
})
