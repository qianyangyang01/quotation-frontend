export type QuotationImageKind = 'snapshot' | 'physical' | 'product'

export type QuotationImageCandidate = {
  kind: QuotationImageKind
  url: string
  label: string
}

export type QuotationImageInput = {
  snapshotImage?: string
  physicalImage?: string
  productImage?: string
}

export function quotationImageCandidates(input: QuotationImageInput): QuotationImageCandidate[] {
  const seen = new Set<string>()
  return [
    { kind: 'snapshot' as const, url: input.snapshotImage, label: '报价商品图' },
    { kind: 'physical' as const, url: input.physicalImage, label: '采购实物图' },
    { kind: 'product' as const, url: input.productImage, label: '采购产品图' },
  ].flatMap(candidate => {
    const url = String(candidate.url || '').trim()
    if (!url || seen.has(url)) return []
    seen.add(url)
    return [{ ...candidate, url }]
  })
}

export function nextQuotationImageCandidate(candidates: QuotationImageCandidate[], failedUrls: ReadonlySet<string>) {
  return candidates.find(candidate => !failedUrls.has(candidate.url)) || null
}

export function preferredQuotationImage(physicalImage?: string, productImage?: string) {
  return quotationImageCandidates({ physicalImage, productImage })[0]?.url || ''
}
