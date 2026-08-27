import { describe, expect, it } from 'vitest'
import { legacyEntryDecision } from './legacyMigrationExport'

describe('legacy migration whitelist', () => {
  it('permanently excludes test and generated procurement records', () => {
    expect(legacyEntryDecision('milano-quotation/purchase-products', [{ sku: 'TESTP260001' }]).decision).toBe('exclude')
    expect(legacyEntryDecision('milano-quotation/purchase-products', [{ sku: 'AUTO-123' }]).decision).toBe('exclude')
  })

  it('keeps verified-looking business records for manual approval', () => {
    expect(legacyEntryDecision('milano-quotation/purchase-products', [{ sku: 'MLN-2026-001', category: '服装' }]).decision).toBe('migrate')
  })

  it('excludes retired supplier master data', () => {
    expect(legacyEntryDecision('milano-quotation/suppliers', [{ code: 'SUP-001' }])).toEqual({
      decision: 'exclude',
      reason: '供应商主数据功能已下线',
    })
  })

  it('always excludes training-system containers even when their names resemble quotation records', () => {
    expect(legacyEntryDecision('milano.training.feedback-records', [{ id: 'feedback-1' }])).toEqual({
      decision: 'exclude',
      reason: '培训系统数据禁止进入报价迁移',
    })
    expect(legacyEntryDecision('milano-training-assets/files', [])).toEqual({
      decision: 'exclude',
      reason: '培训系统数据禁止进入报价迁移',
    })
  })
})
