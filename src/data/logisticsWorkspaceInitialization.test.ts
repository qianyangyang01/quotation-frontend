import { describe, expect, it, vi } from 'vitest'
import type { Dataset } from './logisticsRebuild'
import { initializeLogisticsWorkspace } from './logisticsWorkspaceInitialization'

const active: Dataset = { id: 'active', name: '正式库', status: 'active', revision: 1, created_at: '' }
const requested: Dataset = { id: 'requested', name: '指定库', status: 'preparing', revision: 2, created_at: '' }

describe('logistics workspace initialization', () => {
  it('starts the requested workspace while the dataset list is still loading', async () => {
    let resolveDatasets!: (datasets: Dataset[]) => void
    const datasets = new Promise<Dataset[]>(resolve => { resolveDatasets = resolve })
    const loadWorkspace = vi.fn().mockResolvedValue(undefined)

    const resultPromise = initializeLogisticsWorkspace('requested', () => datasets, loadWorkspace)
    expect(loadWorkspace).toHaveBeenCalledWith('requested')
    resolveDatasets([active, requested])

    await expect(resultPromise).resolves.toEqual({ datasets: [active, requested], datasetId: 'requested' })
    expect(loadWorkspace).toHaveBeenCalledTimes(1)
  })

  it('falls back to the active dataset for a stale bookmark', async () => {
    const loadWorkspace = vi.fn()
      .mockRejectedValueOnce(new Error('missing dataset'))
      .mockResolvedValueOnce(undefined)

    await expect(initializeLogisticsWorkspace('removed', async () => [active], loadWorkspace))
      .resolves.toEqual({ datasets: [active], datasetId: 'active' })
    expect(loadWorkspace).toHaveBeenNthCalledWith(1, 'removed')
    expect(loadWorkspace).toHaveBeenNthCalledWith(2, 'active')
  })

  it('loads the active workspace after resolving a route without a dataset', async () => {
    const loadWorkspace = vi.fn().mockResolvedValue(undefined)

    await expect(initializeLogisticsWorkspace(undefined, async () => [active], loadWorkspace))
      .resolves.toEqual({ datasets: [active], datasetId: 'active' })
    expect(loadWorkspace).toHaveBeenCalledWith('active')
  })
})
