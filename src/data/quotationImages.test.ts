import { describe, expect, it } from 'vitest'
import { nextQuotationImageCandidate, preferredQuotationImage, quotationImageCandidates } from './quotationImages'

describe('quotation image fallback', () => {
  it('prefers the saved quotation image before current purchase images', () => {
    const candidates = quotationImageCandidates({ snapshotImage: '/quote.jpg', physicalImage: '/physical.jpg', productImage: '/product.jpg' })
    expect(candidates.map(candidate => candidate.url)).toEqual(['/quote.jpg', '/physical.jpg', '/product.jpg'])
  })

  it('prefers a physical image before the product image', () => {
    expect(preferredQuotationImage('/physical.jpg', '/product.jpg')).toBe('/physical.jpg')
  })

  it('uses the product image when no physical image exists', () => {
    expect(preferredQuotationImage('', '/product.jpg')).toBe('/product.jpg')
  })

  it('falls back after a physical image fails to load', () => {
    const candidates = quotationImageCandidates({ physicalImage: '/broken.jpg', productImage: '/product.jpg' })
    expect(nextQuotationImageCandidate(candidates, new Set(['/broken.jpg']))?.url).toBe('/product.jpg')
  })

  it('returns no image after every unique candidate fails', () => {
    const candidates = quotationImageCandidates({ snapshotImage: '/same.jpg', physicalImage: '/same.jpg', productImage: '/product.jpg' })
    expect(candidates).toHaveLength(2)
    expect(nextQuotationImageCandidate(candidates, new Set(['/same.jpg', '/product.jpg']))).toBeNull()
  })
})
