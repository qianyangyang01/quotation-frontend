import { expect, it } from 'vitest'
import { withLegacyAutoTax } from './legacyPurchasePricing'
import { normalizePurchaseRecord } from './purchaseStore'
import { purchasePriceBreakdown, bundlePurchaseCost } from '@/services/quotationCalculator'
import { legacyPurchaseDisplay } from '@/services/legacyPurchaseDisplay'

const scenarios = [1,5,99,100,1999,11000,99999999].flatMap(cents =>
  [0,1,40,100,800,1000,1300,10000].map(basisPoints => ({ cents, basisPoints })))

it.each(scenarios)('independent integer oracle: $cents cents with $basisPoints basis points', ({cents,basisPoints}) => {
  // Integer arithmetic is independent of the production Decimal implementation.
  const includedCents = Number((BigInt(cents) * BigInt(10000 + basisPoints) + 5000n) / 10000n)
  const effectiveCents = basisPoints === 0 ? Number((BigInt(cents) * 101n + 50n) / 100n) : includedCents
  let record = normalizePurchaseRecord({ sku:'ACCEPT-PRICE', dataSource:'legacy_2026',
    sourceQuotedPriceCny:cents/100, taxPoint:basisPoints/10000, minOrderQty:2, weightG:135, singleFreightCny:5 })
  for(let round=0;round<5;round++) {
    record=normalizePurchaseRecord(withLegacyAutoTax(record,cents/100))
    expect(record.purchasePriceCny).toBe(includedCents/100)
    expect(record.taxIncludedPriceCny).toBe(includedCents/100)
    expect(purchasePriceBreakdown(record,'10').effectiveUnitPriceCny).toBe(effectiveCents/100)
    expect(legacyPurchaseDisplay(record,'10')!.unitPrice).toBe(cents/100)
    expect(legacyPurchaseDisplay(record,'100+')!.effectiveUnitPrice).toBe(effectiveCents/100)
  }
  const snapshot=JSON.stringify(record)
  const rows=[{sku:record.sku,quantityPerSet:3,purchaseUnitPrice:0,purchaseFreightPerUnit:0,weightKg:.135,customWeightKg:null}]
  expect(bundlePurchaseCost(rows,[record],'10',7)).toBe(effectiveCents*21/100)
  expect(JSON.stringify(record)).toBe(snapshot)
})
