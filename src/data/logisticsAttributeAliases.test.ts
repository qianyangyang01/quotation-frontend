import { expect, it } from 'vitest'
import { normalizeLogisticsAttribute, selectableLogisticsAttributes } from './logisticsAttributes'
import { normalizePolicies, financeAllowsLogisticsChannel } from './financeChannelPolicies'

it('normalizes only the pure battery alias and keeps clothing separate', () => {
  expect(normalizeLogisticsAttribute(' 纯电池 ')).toBe('纯电')
  expect(normalizeLogisticsAttribute('液体')).toBe('液体')
  expect(normalizeLogisticsAttribute('服装')).toBe('服装')
  expect(selectableLogisticsAttributes(['纯电池', '纯电', '液体', '定制'])).toEqual(['普货', '化妆品', '保健品', '带电', '纯电', '服装', '粉末', '非液体化妆品', '带磁', '微敏感', '定制'])
})
it('preserves policy identity without converting liquid policies to clothing', () => {
  const source = [{ id:'old', category:'纯电池', countryRules:[], enabled:true, updatedAt:'' }, { id:'liquid', category:'液体', countryRules:[], enabled:true, updatedAt:'' }]
  const result = normalizePolicies(source)
  expect(result.map(p => [p.id,p.category])).toEqual([['old','纯电'],['liquid','液体']])
  expect(source[0]?.category).toBe('纯电池')
  expect(result.some(p => p.category === '服装')).toBe(false)
  expect(financeAllowsLogisticsChannel(source, '服装', '美国', 1, {carrier:'test',channel:'test',channelCode:'test'})).toBe(false)
})
