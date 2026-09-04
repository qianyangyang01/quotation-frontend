import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const source = readFileSync(new URL('./QuotationRecordsView.vue', import.meta.url), 'utf8')
const normalizedSource = source.replace(/\s+/g, ' ')

describe('quotation record product image', () => {
  it('uses the same image and category fallback chain for personal and company records', () => {
    expect(source).toContain("defineProps<{ scope: 'mine' | 'company' }>()")
    expect(source).toContain("import PurchaseCategoryBadge from '@/components/purchase/PurchaseCategoryBadge.vue'")
    expect(normalizedSource).toContain(':snapshot-image="row.productImage"')
    expect(normalizedSource).toContain(':physical-image="recordPurchaseProduct(row)?.physicalImage"')
    expect(normalizedSource).toContain(':product-image="recordPurchaseProduct(row)?.productImage"')
    expect(normalizedSource).toContain('<template #fallback><PurchaseCategoryBadge :category="recordPurchaseProduct(row)?.category || row.productCategory" /></template>')
  })

  it('does not fall back to a product-name initial', () => {
    expect(source).not.toContain('row.productSummary.slice(0,1)')
  })
})
