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
