import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const view = readFileSync(new URL('./LogisticsWorkspaceView.vue', import.meta.url), 'utf8').replace(/\s+/g, ' ')
const service = readFileSync(new URL('../data/logisticsRebuild.ts', import.meta.url), 'utf8')

describe('logistics standardized review export', () => {
  it('adds the same-style action to batch and version review without removing existing exports', () => {
    expect(view.match(/>导出关键字段<\/button>/g)).toHaveLength(2)
    expect(view).toContain('service.exportBatchStandardized(batch!.id)')
    expect(view).toContain('service.exportVersionStandardized(version!)')
    expect(view).toContain('service.exportBatchDiff(batch!.id)')
    expect(view).toContain('service.exportPrices(datasetId, versionFilters(version!.id))')
    expect(view).toContain("busy || batch.status !== 'completed'")
  })
  it('uses authenticated prepared downloads with distinct batch and version kinds', () => {
    expect(service).toContain("kind: 'batch-standardized'")
    expect(service).toContain("kind: 'version-standardized'")
    expect(service).toContain("kind: 'batch-diff'")
    expect(service).toContain("kind: 'prices'")
  })
})
