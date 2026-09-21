import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import type { LogisticsRateRow } from './logisticsWorkbook'

const path = process.env.JISU_BILLING_CASES
it.skipIf(!path)('quotes the real Jisu workbook with the same minimum and tier totals as Java', () => {
  const evidence: { channel: { channelName: string; providerName: string; rows: LogisticsRateRow[] }; cases: Array<{ actual: number; total: number; chargeWeightKg: number }> } = JSON.parse(readFileSync(path!, 'utf8'))
  const channel = evidence.channel
  const rule: LogisticsRule = {
    id: 1, name: channel.channelName, englishName: '', type: '专线', currency: 'CNY', published: 'test',
    status: '启用', dates: '', users: '', phoneRequired: false, areaCount: 1, priceRowCount: channel.rows.length, billingVerified: true,
    relations: [{ carrier: channel.providerName, channel: channel.channelName, channelCode: 'JS02-邮编敏感货-2', discounts: '' }],
    prices: channel.rows.map(normalizeLogisticsPriceRow),
  }
  expect(rule.prices).toHaveLength(9)
  expect(evidence.cases).toHaveLength(21)
  for (const item of evidence.cases) {
    expect(calculateLogisticsFee(rule, 'US', item.actual, ['非液体化妆品']), `${item.actual} kg`).toMatchObject({
      actualWeightKg: item.actual, chargeWeightKg: item.chargeWeightKg, minChargeWeightKg: .05, total: item.total,
    })
  }
  expect(calculateLogisticsFee(rule, 'US', 5.001, ['非液体化妆品'])).toBeNull()
  expect(calculateLogisticsFee(rule, 'CA', .05, ['非液体化妆品'])).toBeNull()
})
