// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import type { Batch } from '@/data/logisticsRebuild'

const mocks = vi.hoisted(() => ({ batch: vi.fn(), publish: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ replace: vi.fn() }) }))
vi.mock('@/components/logistics/CompanyChannelDirectory.vue', () => ({ default: { render: () => null } }))
vi.mock('@/data/publishedLogisticsRepository', () => ({ invalidatePublishedLogisticsCache: vi.fn() }))
vi.mock('@/data/logisticsRebuild', async importOriginal => {
  const actual = await importOriginal<typeof import('@/data/logisticsRebuild')>()
  const dataset = { id: 'dataset', name: '当前库', status: 'active', revision: 1, created_at: '' }
  return { ...actual, logisticsRebuild: { ...actual.logisticsRebuild,
    datasets: async () => [dataset],
    workspace: async () => ({ dataset, providers: [], channels: [], versions: [] }),
    batch: mocks.batch, publishReady: mocks.publish,
    publishProgress: async () => ({ batchId: 'batch', publishedVersionIds: [] }),
  } }
})
import LogisticsWorkspaceView from './LogisticsWorkspaceView.vue'

function batch(token = 'v4'): Batch {
  return { id: 'batch', dataset_id: 'dataset', status: 'completed', phase: 'review', created_at: '', payload: {
    progress: 100, files: [{ name: '新增渠道.xlsx' }], results: [{ providerName: '燕文', channelName: '新渠道', channelId: 'new', versionId: 'draft', status: 'draft', pricingReady: true, errors: 0, priceRows: 1, summary: {} }],
    coverage: { token, existingChannels: 1, coveredChannels: 0, missingCount: 1, partial: true, missingChannels: [{ channelId: 'old', providerName: '燕文', channelName: '燕文化妆品专线', versionId: token, versionNumber: 4, sourceFile: '9月10日报价.xlsx' }] },
  } }
}
let app: App | undefined
afterEach(() => { app?.unmount(); app = undefined; document.body.innerHTML = ''; sessionStorage.clear(); vi.clearAllMocks() })

describe('partial logistics import review UI', () => {
  it('blocks publication until acknowledged, resets on refresh, and sends the reviewed scope', async () => {
    sessionStorage.setItem('milano.logistics.active-batch.dataset', 'batch')
    mocks.batch.mockResolvedValue(batch())
    mocks.publish.mockRejectedValue(new Error('测试到请求边界'))
    const host = document.createElement('div'); document.body.append(host)
    app = createApp(LogisticsWorkspaceView); app.mount(host)
    await vi.waitFor(() => expect(host.textContent).toContain('燕文化妆品专线'))
    const button = () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(el => el.textContent?.includes('一键发布 1 个可用渠道'))!
    const note = host.querySelector<HTMLInputElement>('input[placeholder="填写价格来源和审核结论"]')!
    note.value = '本次仅新增渠道'; note.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
    expect(button().disabled).toBe(true)
    expect(host.textContent).toContain('9月10日报价.xlsx')
    const confirm = () => host.querySelector<HTMLInputElement>('.import-coverage input[type="checkbox"]')!
    confirm().click(); await nextTick(); expect(button().disabled).toBe(false)
    mocks.batch.mockResolvedValue(batch('v5'))
    ;[...host.querySelectorAll<HTMLButtonElement>('button')].find(el => el.textContent === '重新核对覆盖范围')!.click()
    await vi.waitFor(() => expect(confirm().checked).toBe(false))
    expect(button().disabled).toBe(true)
    confirm().click(); await nextTick(); button().click()
    await vi.waitFor(() => expect(mocks.publish).toHaveBeenCalledOnce())
    expect(mocks.publish.mock.calls[0]?.[4]).toEqual({ coverageToken: 'v5', partialUpdateConfirmed: true })
  })
})

describe('filtered logistics import feedback', () => {
  function unmatchedReport(): NonNullable<Batch['payload']['fileReports']>[number] {
    return { fileName: '容鼎10月19日.xlsx', status: 'filtered', sheets: [{ name: '美国商派', status: 'filtered', channelMatches: [{ providerName: '容鼎', channelName: '美国标准DDP专线-普货A(SP)', sourceRow: 22, status: 'filtered', reason: '渠道未登记或未登记该名称别名' }] }] }
  }
  async function mountBatch(value: Batch) {
    sessionStorage.setItem('milano.logistics.active-batch.dataset', 'batch')
    mocks.batch.mockResolvedValue(value)
    const host = document.createElement('div'); document.body.append(host)
    app = createApp(LogisticsWorkspaceView); app.mount(host)
    await vi.waitFor(() => expect(host.textContent).toContain('物流价格批量审核'))
    return host
  }
  it('shows a prominent no-update reason and no completed publication for zero matches', async () => {
    const value = batch(); value.payload.results = []; value.payload.files = [{ name: '容鼎10月19日.xlsx' }]; value.payload.fileReports = [unmatchedReport()]
    const host = await mountBatch(value)
    const alert = host.querySelector('.filtered-import-file[role="alert"]')!
    expect(alert.textContent).toContain('容鼎10月19日.xlsx')
    expect(alert.textContent).toContain('未匹配到可用的公司渠道，未生成价格草稿、未更新运费')
    expect(host.textContent).not.toContain('首重续重已过滤')
    expect(host.textContent).not.toContain('本批渠道检查通过')
    expect(host.querySelector('.review-steps > span:last-child')?.classList.contains('done')).toBe(false)
    expect(host.querySelector<HTMLDetailsElement>('.batch-channel-matches')?.open).toBe(true)
    expect(host.querySelector<HTMLDetailsElement>('.batch-source-files')?.open).toBe(true)
    expect(host.querySelector<HTMLButtonElement>('.publish-all')?.disabled).toBe(true)
    expect(mocks.publish).not.toHaveBeenCalled()
  })
  it('names an unmatched file while retaining review results for the other file', async () => {
    const value = batch(); value.payload.files.push({ name: '容鼎10月19日.xlsx' }); value.payload.fileReports = [{ fileName: '新增渠道.xlsx', status: 'parsed' }, unmatchedReport()]
    const host = await mountBatch(value)
    expect(host.querySelector('.filtered-import-file[role="alert"]')?.textContent).toContain('容鼎10月19日.xlsx')
    expect(host.querySelector('.batch-results-table')?.textContent).toContain('新渠道')
    expect(host.querySelector('.review-publish-bar')?.textContent).toContain('1 个文件需处理')
    expect(host.querySelector<HTMLButtonElement>('.publish-all')?.disabled).toBe(true)
    expect(mocks.publish).not.toHaveBeenCalled()
  })
  it('keeps genuine first-next exclusions informational and never marks an empty batch as published', async () => {
    const value = batch(); value.payload.results = []; value.payload.fileReports = [{ fileName: '首重续重.xlsx', status: 'filtered', sheets: [{ name: '首重价格', status: 'filtered', filteredFirstNextRows: [3, 4] }] }]
    const host = await mountBatch(value)
    expect(host.querySelector('.filtered-import-file[role="status"]')?.textContent).toContain('首重续重价格已过滤')
    expect(host.querySelector('.filtered-import-file[role="alert"]')).toBeNull()
    expect(host.querySelector('.review-steps > span:last-child')?.classList.contains('done')).toBe(false)
    expect(host.querySelector<HTMLButtonElement>('.publish-all')?.disabled).toBe(true)
  })
  it('does not mark identity conflicts complete when the other imported channel is unchanged', async () => {
    const value = batch(); value.payload.results[0]!.status = 'unchanged'
    value.payload.fileReports = [{ fileName: '渠道归属冲突.xlsx', status: 'match-pending', sheets: [{ name: '价格', status: 'match-pending', channelMatches: [{ providerName: '容鼎', channelName: '冲突渠道', sourceRow: 22, status: 'ambiguous', reason: '产品代码与渠道名称指向不同渠道' }] }] }]
    const host = await mountBatch(value)
    expect(host.querySelector('.filtered-import-file[role="alert"]')?.textContent).toContain('渠道归属待确认')
    expect(host.querySelector('.review-steps > span:last-child')?.classList.contains('done')).toBe(false)
    expect(host.querySelector('.review-publish-bar')?.textContent).toContain('1 个文件需处理')
    expect(host.textContent).not.toContain('本批渠道检查通过')
    expect(host.querySelector<HTMLDetailsElement>('.batch-channel-matches')?.open).toBe(true)
    expect(host.querySelector<HTMLDetailsElement>('.batch-file-list > details')?.open).toBe(true)
    expect(host.querySelector<HTMLButtonElement>('.publish-all')?.disabled).toBe(true)
  })
})
