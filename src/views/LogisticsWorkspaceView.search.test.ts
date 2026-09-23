// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'

const mocks = vi.hoisted(() => ({ prices: vi.fn(), replace: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: { logisticsTab: 'prices', page: '3' } }), useRouter: () => ({ replace: mocks.replace }) }))
vi.mock('@/components/logistics/CompanyChannelDirectory.vue', () => ({ default: { render: () => null } }))
vi.mock('@/data/logisticsRebuild', async importOriginal => {
  const actual = await importOriginal<typeof import('@/data/logisticsRebuild')>()
  const dataset = { id: 'dataset', name: '当前库', status: 'active', revision: 1, created_at: '' }
  return { ...actual, logisticsRebuild: { ...actual.logisticsRebuild,
    datasets: async () => [dataset],
    workspace: async () => ({ dataset, providers: [], channels: [], versions: [] }),
    prices: mocks.prices,
  } }
})
import LogisticsWorkspaceView from './LogisticsWorkspaceView.vue'

let app: App | undefined
afterEach(() => { app?.unmount(); app = undefined; document.body.innerHTML = ''; sessionStorage.clear(); vi.clearAllMocks() })

it('submits combined terms and country together, resets pagination, and distinguishes no match from an empty library', async () => {
  mocks.prices.mockResolvedValue({ items: [], total: 31, page: 2, size: 10, totalPages: 4 })
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(LogisticsWorkspaceView); app.mount(host)
  await vi.waitFor(() => expect(host.textContent).toContain('共 31 条正式价格'))
  const form = host.querySelector<HTMLFormElement>('.modern-filters')!
  const inputs = form.querySelectorAll<HTMLInputElement>('input')
  const fill = async (input: HTMLInputElement, value: string) => {
    input.value = value; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  }
  await fill(inputs[0]!, ' 云途 化妆品 '); await fill(inputs[1]!, ' 科威特 ')
  mocks.prices.mockResolvedValue({ items: [{ providerName: '云途', channelName: '云途全球化妆品类专线挂号', countryCode: 'KW', areaName: '科威特', weightFromKg: 0, weightToKg: 1, pricePerKg: 50, registrationFee: 20, versionNumber: 1 }], total: 1, page: 0, size: 10, totalPages: 1 })
  form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await vi.waitFor(() => expect(host.textContent).toContain('云途全球化妆品类专线挂号'))
  const [dataset, filters] = mocks.prices.mock.calls.at(-1)!
  expect(dataset).toBe('dataset')
  expect(Object.fromEntries(filters)).toEqual({ query: '云途 化妆品', country: '科威特', page: '0', size: '10' })
  expect(host.querySelector('tbody')?.textContent).toContain('50.00')
  await fill(inputs[0]!, '不存在的渠道')
  mocks.prices.mockResolvedValue({ items: [], total: 0, page: 0, size: 10, totalPages: 0 })
  form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await vi.waitFor(() => expect(host.textContent).toContain('没有匹配的运费规则'))
  expect(host.querySelector('tbody')?.textContent).not.toContain('导入')
  form.querySelector<HTMLButtonElement>('button[type="button"]')!.click()
  await vi.waitFor(() => expect(host.textContent).toContain('当前没有正式价格'))
  expect(Object.fromEntries(mocks.prices.mock.calls.at(-1)![1])).toEqual({ query: '', country: '', page: '0', size: '10' })
})
