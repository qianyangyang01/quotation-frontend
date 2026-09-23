import { describe, expect, it } from 'vitest'
import { coveragePublishInput, coveragePublishSummary, type ImportCoverage } from './logisticsImportCoverage'

const partial: ImportCoverage = { existingChannels: 8, coveredChannels: 3, missingCount: 5, partial: true, token: 'scope-v4', missingChannels: [] }
describe('import coverage publishing', () => {
  it('fails closed when coverage has not loaded or partial scope was not reviewed', () => {
    expect(() => coveragePublishInput(undefined, true)).toThrow('尚未完成')
    expect(() => coveragePublishInput(partial, false)).toThrow('未更新渠道')
  })
  it('sends the exact reviewed token and explicit partial confirmation', () => {
    expect(coveragePublishInput(partial, true)).toEqual({ coverageToken: 'scope-v4', partialUpdateConfirmed: true })
    expect(coveragePublishSummary(partial)).toContain('5 个现行渠道未纳入本批')
  })
  it('does not require partial confirmation for full coverage or claim all prices are current', () => {
    const full = { ...partial, missingCount: 0, partial: false }
    expect(coveragePublishInput(full, false).partialUpdateConfirmed).toBe(false)
    expect(coveragePublishSummary(full)).toBe('')
  })
})
