import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { checkFobFinanceVersion } from './fobFinanceFreshness'
import { appUpdate } from './appUpdate'
import { loadQuotationSync } from './quotationSync'
const finance = vi.hoisted(() => ({ hydrated: true, loading: false, version: 3 as number | undefined, hydrate: vi.fn() }))
vi.mock('./financeSettings', () => ({
  financeSettingsAreHydrated: () => finance.hydrated,
  financeSettingsAreLoading: () => finance.loading,
  financeSettingVersions: () => ({ 'exchange-rate': finance.version }),
  hydrateFinanceSettings: finance.hydrate,
}))
vi.mock('./quotationSync', () => ({ loadQuotationSync: vi.fn() }))
beforeEach(() => {
  finance.hydrated = true; finance.loading = false; finance.version = 3; finance.hydrate.mockClear()
  vi.mocked(loadQuotationSync).mockReset()
  vi.spyOn(appUpdate, 'requireUpdate').mockImplementation(() => undefined)
})
afterEach(() => vi.restoreAllMocks())
const signal = () => new AbortController().signal
const snapshot = (version = 3) => ({ purchaseVersions: {}, logisticsRevision: '', financeVersions: { 'exchange-rate': version } })

it('requires refresh when FOB exchange-rate changes without replacing cache or form state', async () => {
  vi.mocked(loadQuotationSync).mockResolvedValue(snapshot(4))
  await checkFobFinanceVersion(signal())
  expect(loadQuotationSync).toHaveBeenCalledWith([], expect.any(AbortSignal))
  expect(appUpdate.requireUpdate).toHaveBeenCalledWith('exchange-rate')
  expect(finance.hydrate).not.toHaveBeenCalled()
  expect(finance.version).toBe(3)
  expect(finance.hydrated).toBe(true)
})

it('does not interrupt FOB for unchanged exchange rate or unrelated financial settings', async () => {
  vi.mocked(loadQuotationSync).mockResolvedValue({ ...snapshot(), financeVersions: { 'exchange-rate': 3, 'tax-settings': 99 } })
  await checkFobFinanceVersion(signal())
  expect(appUpdate.requireUpdate).not.toHaveBeenCalled()
})

it.each(['loading', 'unhydrated', 'missing'] as const)('does not invent an applied rate baseline when %s', async state => {
  if (state === 'loading') finance.loading = true
  if (state === 'unhydrated') finance.hydrated = false
  if (state === 'missing') finance.version = undefined
  await checkFobFinanceVersion(signal())
  expect(loadQuotationSync).not.toHaveBeenCalled()
})

it('preserves the form and reports unreadable versions rather than declaring an update', async () => {
  vi.mocked(loadQuotationSync).mockResolvedValue({ purchaseVersions: {}, logisticsRevision: '' })
  await expect(checkFobFinanceVersion(signal())).rejects.toThrow('无法确认')
  expect(appUpdate.requireUpdate).not.toHaveBeenCalled()
  expect(finance.hydrate).not.toHaveBeenCalled()
})

it('ignores checks cancelled by leaving the page', async () => {
  const controller = new AbortController()
  vi.mocked(loadQuotationSync).mockImplementation(async () => { controller.abort(); return snapshot(4) })
  await expect(checkFobFinanceVersion(controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
  expect(appUpdate.requireUpdate).not.toHaveBeenCalled()
})

it('ignores a response belonging to an explicitly reloaded cache/account', async () => {
  vi.mocked(loadQuotationSync).mockImplementation(async () => { finance.version = 4; return snapshot(3) })
  await checkFobFinanceVersion(signal())
  expect(appUpdate.requireUpdate).not.toHaveBeenCalled()
})
