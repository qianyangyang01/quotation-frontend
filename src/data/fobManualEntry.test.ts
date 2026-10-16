import { describe, expect, it } from 'vitest'
import { emptyFobManualDraft, fobManualPatch } from './fobManualEntry'
function draft() {
  return { ...emptyFobManualDraft(), sku: 'pf2600100', minOrderQty: '100', orderMultiple: '20',
    freightQuantity: '100', freightTotal: '35', tiers: [{ minQty: '100', maxQty: '499', price: '12.50' }, { minQty: '500', maxQty: '', price: '11.80' }] }
}
describe('FOB manual entry contract', () => {
  it('serializes independent MOQ/multiple, full tiers and explicit batch freight without a source override', () => {
    expect(fobManualPatch(draft())).toMatchObject({ sku: 'PF2600100', moqRaw: '100件起订;20的倍数下单', priceRaw: '100-499件单价12.5\n500件起单价11.8', freightRaw: '100件运费35' })
    expect(fobManualPatch(draft())).not.toHaveProperty('dataSource')
  })
  it('rejects gaps, overlaps and unknown freight instead of changing quotation rules', () => {
    for (const start of ['499', '501']) {
      const value = draft(); value.tiers[1]!.minQty = start
      expect(() => fobManualPatch(value)).toThrow('连续')
    }
    expect(() => fobManualPatch({ ...draft(), freightQuantity: '10' })).toThrow('100件')
    expect(() => fobManualPatch({ ...draft(), freightTotal: '' })).toThrow('免费请明确填写0')
    expect(() => fobManualPatch({ ...draft(), orderMultiple: '0' })).toThrow('下单倍数')
    expect(() => fobManualPatch({ ...draft(), sku: 'DEMO-FOB-001' })).toThrow('正式SKU')
  })
  it('retains zero freight and zero price, and rejects an open tier followed by another tier', () => {
    const value = draft(); value.freightTotal = '0'; value.tiers[0]!.price = '0'
    expect(fobManualPatch(value).freightRaw).toBe('100件运费0')
    value.tiers[0]!.maxQty = ''
    expect(() => fobManualPatch(value)).toThrow('连续')
  })
})
