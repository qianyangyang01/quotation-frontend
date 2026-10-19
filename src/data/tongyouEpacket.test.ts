import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { calculateLogisticsFee, logisticsQuoteRegions, replaceLogisticsRules, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import type { LogisticsRateRow } from './logisticsWorkbook'

it.skipIf(!process.env.TONGYOU_EPACKET_SOURCE)('requires a supported Spanish postal zone and respects the source weight limit for all three goods types', () => {
  const channel = JSON.parse(readFileSync(process.env.TONGYOU_EPACKET_SOURCE!, 'utf8')) as { rows: LogisticsRateRow[] }
  const rule: LogisticsRule = { id: 999, name: 'TY-E邮宝', englishName: '', type: '专线', currency: 'CNY', published: 'test', status: '启用', dates: '', users: '',
    relations: [{ carrier: '通邮', channel: 'TY-E邮宝', channelCode: 'test', discounts: '' }], phoneRequired: false, areaCount: 1, priceRowCount: 5, billingVerified: true, prices: channel.rows.map(normalizeLogisticsPriceRow) }
  replaceLogisticsRules([rule])
  try {
    expect(logisticsQuoteRegions('ES')).toHaveLength(5)
    for (const mark of ['普货', '带电', '化妆品']) for (const prefix of ['51', '35', '38', '52', '07']) {
      const zone = `邮编${prefix}开头`
      for (const [weight, total] of [[0.001, 20.11], [0.1, 30.8], [0.5, 74], [1, 128], [2, 236]]) expect(calculateLogisticsFee(rule, 'ES', weight!, [mark], undefined, zone)?.total).toBe(total)
      expect(calculateLogisticsFee(rule, 'ES', 2.001, [mark], undefined, zone)).toBeNull()
    }
    expect(calculateLogisticsFee(rule, 'ES', 0.5, ['普货'])).toBeNull()
    expect(calculateLogisticsFee(rule, 'ES', 0.5, ['普货'], undefined, '邮编28开头')).toBeNull()
    expect(calculateLogisticsFee(rule, 'FR', 0.5, ['普货'], undefined, '邮编51开头')).toBeNull()
  } finally { replaceLogisticsRules([]) }
})
