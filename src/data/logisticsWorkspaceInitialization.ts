import type { Dataset } from '@/data/logisticsRebuild'

type LoadDatasets = () => Promise<Dataset[]>
type LoadWorkspace = (datasetId: string) => Promise<void>

function requestedId(value: unknown) {
  const candidate = Array.isArray(value) ? value[0] : value
  return typeof candidate === 'string' ? candidate.trim() : ''
}

function defaultDatasetId(datasets: Dataset[]) {
  return datasets.find(dataset => dataset.status === 'active')?.id || datasets[0]?.id || ''
}

export async function initializeLogisticsWorkspace(
  queryDataset: unknown,
  loadDatasets: LoadDatasets,
  loadWorkspace: LoadWorkspace,
) {
  const requested = requestedId(queryDataset)
  const datasetsPromise = loadDatasets()

  if (!requested) {
    const datasets = await datasetsPromise
    const datasetId = defaultDatasetId(datasets)
    if (datasetId) await loadWorkspace(datasetId)
    return { datasets, datasetId }
  }

  const workspacePromise = loadWorkspace(requested)
  const [datasetsResult, workspaceResult] = await Promise.allSettled([datasetsPromise, workspacePromise])
  if (datasetsResult.status === 'rejected') throw datasetsResult.reason

  const datasets = datasetsResult.value
  if (datasets.some(dataset => dataset.id === requested)) {
    if (workspaceResult.status === 'rejected') throw workspaceResult.reason
    return { datasets, datasetId: requested }
  }

  const datasetId = defaultDatasetId(datasets)
  if (datasetId) await loadWorkspace(datasetId)
  return { datasets, datasetId }
}
