// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import BatchSheet from './BatchSingleSheet.vue'
import { appUpdate } from '@/services/appUpdate'
import type { CustomerQuoteSheet } from '@/data/customerQuoteSheet'
import { copyQuotationText } from '@/services/customerQuoteSheetClipboard'
import { copyQuoteSheetImage } from '@/services/customerQuoteSheetRenderer'

vi.mock('@/services/appUpdate', async () => {
  const { reactive } = await import('vue')
  const state = reactive({ updateRequired: false })
  return { appUpdate: { state, assertCurrent: () => { if (state.updateRequired) throw new Error('系统已更新，请刷新') } } }
})
vi.mock('@/services/customerQuoteSheetRenderer', async importOriginal => ({
  ...await importOriginal<typeof import('@/services/customerQuoteSheetRenderer')>(),
  renderCustomerQuoteSheet: vi.fn().mockImplementation(async () => [{ blob: new Blob(['png'], { type: 'image/png' }), width: 10, height: 10, firstRow: 1, lastRow: 1 }]),
}))

const version = appUpdate.state as { updateRequired: boolean }
const sheet: CustomerQuoteSheet = { agent: 'Agent', date: '23 Oct 2026', quantityLabels: ['1 pc'], issues: [], rows: [{ key: 'new', number: 1, sku: 'NEW-SKU', country: 'US', provider: '4PX', shippingTime: '7 days', prices: [12.34], sourceDescription: '' }] }
let app: App
const tick = async () => { for (let i = 0; i < 12; i++) await nextTick() }
function mount(currentQuote = true, withHtml = false) {
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(BatchSheet, { sheet: withHtml ? { ...sheet, sizeRules: 'Size M' } : sheet, currentQuote })
  app.mount(host)
}
function clickButton(label: string) { [...document.querySelectorAll('button')].find(button => button.textContent === label)!.click() }

beforeEach(() => {
  version.updateRequired = false
  vi.stubGlobal('isSecureContext', true)
  vi.stubGlobal('ClipboardItem', class { constructor(public data: Record<string, Blob>) {} })
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:quote')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
  Object.defineProperty(document, 'execCommand', { value: () => false, configurable: true })
})
afterEach(async () => { await tick(); app?.unmount(); document.body.innerHTML = ''; vi.unstubAllGlobals(); vi.restoreAllMocks() })

it.each([
  [true, 'data'], [false, 'data'], [true, 'image'], [false, 'image'],
] as const)('rechecks current quote status before a delayed Web Locks clipboard write (currentQuote=%s, kind=%s)', async (currentQuote, kind) => {
  let runLock!: () => Promise<void>
  const writeText = vi.fn().mockResolvedValue(undefined), write = vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('navigator', {
    clipboard: { writeText, write },
    locks: { request: vi.fn((_name, _options, callback) => new Promise<void>((resolve, reject) => {
      runLock = async () => { try { await callback({ name: 'quotation-customer-sheet-clipboard' }); resolve() } catch (error) { reject(error) } }
    })) },
  })
  mount(currentQuote); await tick()
  if (kind === 'image') { clickButton('预览报价图片'); await tick() }
  clickButton(kind === 'image' ? '复制报价图片' : '复制报价数据'); await tick()
  expect(writeText).not.toHaveBeenCalled()
  expect(write).not.toHaveBeenCalled()
  version.updateRequired = true
  await runLock(); await tick()
  expect(kind === 'image' ? write : writeText).toHaveBeenCalledTimes(currentQuote ? 0 : 1)
})

it('does not start a text fallback after an update arrives during HTML clipboard rejection', async () => {
  let rejectHtml!: (reason: Error) => void
  const write = vi.fn(() => new Promise<void>((_resolve, reject) => { rejectHtml = reject }))
  const writeText = vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('navigator', { clipboard: { write, writeText } })
  mount(true, true); await tick(); clickButton('复制报价数据'); await tick()
  expect(write).toHaveBeenCalledOnce()
  version.updateRequired = true
  rejectHtml(new Error('HTML unsupported'))
  await tick()
  expect(writeText).not.toHaveBeenCalled()
})

it('preserves the guard error instead of swallowing it as HTML fallback or permission failure', async () => {
  let rejectHtml!: (reason: Error) => void
  const write = vi.fn(() => new Promise<void>((_resolve, reject) => { rejectHtml = reject }))
  const writeText = vi.fn().mockResolvedValue(undefined)
  const guardError = new Error('CLIENT_UPDATE_REQUIRED')
  const check = () => { if (version.updateRequired) throw guardError }
  vi.stubGlobal('navigator', { clipboard: { write, writeText } })
  const pending = copyQuotationText('prices', '<table>prices</table>', check)
  const assertion = expect(pending).rejects.toBe(guardError)
  await tick(); version.updateRequired = true; rejectHtml(new Error('HTML unsupported'))
  await assertion
  expect(writeText).not.toHaveBeenCalled()
  await expect(copyQuoteSheetImage(new Blob(['png']), check)).rejects.toBe(guardError)
  expect(write).toHaveBeenCalledOnce()
})

it('allows a clipboard operation already handed to the browser to settle without starting another write', async () => {
  let finish!: () => void
  const writeText = vi.fn(() => new Promise<void>(resolve => { finish = resolve }))
  vi.stubGlobal('navigator', { clipboard: { writeText } })
  mount(); await tick(); clickButton('复制报价数据'); await tick()
  expect(writeText).toHaveBeenCalledOnce()
  version.updateRequired = true; finish(); await tick()
  expect(writeText).toHaveBeenCalledOnce()
  expect(document.body.textContent).toContain('当前未保存报价已暂停')
})
