// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import CustomerQuoteSheet from './CustomerQuoteSheet.vue'
import { renderCustomerQuoteSheet, type QuoteSheetImage } from '@/services/customerQuoteSheetRenderer'
import type { QuoteSheetPriceCalculator, QuoteSheetSourceRow } from '@/data/customerQuoteSheet'
import { mapAveragePlans } from '@/data/quoteChannelAverage'
import { quoteSheetRowKey } from '@/data/customerQuoteSheet'
import { captureQuoteRowOrder } from '@/data/quoteSheetRowOrder'
import type { CustomerPriceSnapshot } from '@/data/customerQuotePrices'
import { authState, type AuthUser } from '@/data/authStore'

const layoutUser = (id: string): AuthUser => ({ id, account: id, name: id, role: 'employee', status: 'enabled', mustChangePassword: false, passwordUpdatedAt: '' })

vi.mock('@/services/customerQuoteSheetRenderer', async importOriginal => ({
  ...await importOriginal<typeof import('@/services/customerQuoteSheetRenderer')>(),
  renderCustomerQuoteSheet: vi.fn(),
}))
let app: App
let exposed: InstanceType<typeof CustomerQuoteSheet>
const render = vi.mocked(renderCustomerQuoteSheet)
const writeText = vi.fn(), write = vi.fn(), revoke = vi.fn()
function row(key = 'one', carrier = '花海'): QuoteSheetSourceRow {
  return { country: '美国', quoteRegion: '全国统一', carrier, channelKey: key, ruleId: 1, rule: '内部规则', channelCode: key, transport: '内部渠道', eta: '8～12 天', quote1: 10.8, quote2: 16.35, quote3: 21.9, quoteCustom: 30.85 }
}
function png(firstRow = 1, lastRow = 1): QuoteSheetImage {
  return { blob: new Blob(['png'], { type: 'image/png' }), width: 1536, height: 1024, firstRow, lastRow }
}
function mount(rows = [row()], calculatePrice?: QuoteSheetPriceCalculator, initialQuote?:CustomerPriceSnapshot, recordMode = false) {
  const state = reactive({ canRemoveRows: false, removalDisabled: false, skus: ['SKU-001'], rows, countries: [], salesperson: 'Alex', contextKey: 'product-1', customQuantity: 5, bundle: false, sourcePending: false, calculatePrice, initialQuote, recordMode, initialSystemQuote: undefined as CustomerPriceSnapshot | undefined, resetKey: undefined as string | undefined })
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(CustomerQuoteSheet, { ...state, ref:(instance:unknown)=>{exposed=instance as typeof exposed} }) }); app.mount(host)
  return state
}
function button(label: string) { return [...document.querySelectorAll('button')].find(button => button.textContent === label)! }
async function settle() { for (let i = 0; i < 8; i++) await nextTick() }
async function click(label: string) { button(label).click(); await settle() }
async function input(label: string, value: string) {
  const field = document.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!
  field.value = value; field.dispatchEvent(new Event('input', { bubbles: true })); await settle()
}
beforeEach(() => {
  localStorage.clear()
  authState.current = layoutUser('account-a')
  vi.clearAllMocks()
  render.mockReset().mockResolvedValue([png()])
  write.mockReset().mockResolvedValue(undefined); writeText.mockReset().mockResolvedValue(undefined)
  vi.stubGlobal('isSecureContext', true)
  vi.stubGlobal('navigator', { clipboard: { writeText, write } })
  vi.stubGlobal('ClipboardItem', class { constructor(public items: Record<string, Blob>) {} })
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:quote')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(revoke)
})
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; authState.current = null; localStorage.clear(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

it('keeps the English zone when switching country format, previewing and copying a saved quote', async () => {
  const source = { ...row(), country: '澳大利亚', quoteRegion: '澳大利亚2区' }
  mount([source], undefined, undefined, true)
  const before = exposed.capturePrices()
  expect(document.querySelector<HTMLInputElement>('[aria-label="第 1 行英文分区"]')?.value).toBe('Zone 2')
  await click('二字码'); await click('预览报价单')
  expect(render.mock.lastCall![0].rows[0]).toMatchObject({ country: 'AU', region: 'Zone 2' })
  await click('复制报价数据')
  expect(writeText.mock.lastCall![0]).toContain('AU · Zone 2')
  expect(exposed.capturePrices()).toEqual(before)
})

it('does not reprice custom quantity routes when editing presentation or customer amounts', async () => {
  const calculate = vi.fn((_row: QuoteSheetSourceRow, quantity: number) => quantity * 2)
  mount(Array.from({ length: 30 }, (_, i) => row(`perf-${i}`)), calculate)
  await settle()
  await input('第 4 个价格列数量', '7')
  expect(calculate.mock.calls.length).toBeLessThanOrEqual(30)
  calculate.mockClear()
  await input('报价单署名', 'Updated agent')
  await input('第 1 行第 4 列美元价格', '18.50')
  expect(calculate).not.toHaveBeenCalled()
  expect(exposed.capturePrices().rows[0]!.prices[3]).toBe(18.5)
  expect(exposed.capturePrices().rows[0]!.systemPrices[3]).toBe(14)
})

it('refreshes shared prices when calculator dependencies or source rows change and suspends pending calculations', async () => {
  const pricing = reactive({ multiplier: 2 })
  const calculate = vi.fn((_row: QuoteSheetSourceRow, quantity: number) => quantity * pricing.multiplier)
  const state = mount([row()], calculate)
  await input('第 4 个价格列数量', '7')
  expect(exposed.capturePrices().rows[0]!.systemPrices[3]).toBe(14)
  calculate.mockClear()
  pricing.multiplier = 3; await settle()
  expect(calculate).toHaveBeenCalledTimes(1)
  expect(exposed.capturePrices().rows[0]!.systemPrices[3]).toBe(21)
  state.rows[0]!.quote1 = 99; await settle()
  expect(exposed.capturePrices().rows[0]!.systemPrices[0]).toBe(99)
  state.sourcePending = true; await settle(); calculate.mockClear()
  pricing.multiplier = 4; await settle()
  expect(calculate).not.toHaveBeenCalled()
  expect(() => exposed.capturePrices()).toThrow('报价仍在计算')
  state.sourcePending = false; await settle()
  expect(calculate).toHaveBeenCalledTimes(1)
  expect(exposed.capturePrices().rows[0]!.systemPrices[3]).toBe(28)
})

it('uses only saved historical system prices for custom quantities even when a live calculator is supplied', async () => {
  const calculate = vi.fn(() => 999)
  const state = mount([row()], calculate, { quantities: [1, 2, 3, 7], rows: [{ optionId: 'one', prices: [11, 17, 22, 18.5] }] }, true)
  state.initialSystemQuote = { quantities: [1, 2, 3, 7], rows: [{ optionId: 'one', prices: [10.8, 16.35, 21.9, 14] }] }
  await settle()
  expect(calculate).not.toHaveBeenCalled()
  expect(exposed.capturePrices().rows[0]!.systemPrices[3]).toBe(14)
  await input('报价单署名', 'New viewer')
  expect(calculate).not.toHaveBeenCalled()
  expect(exposed.capturePrices().rows[0]!.prices[3]).toBe(18.5)
})

const contactValue = (label: string) => document.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!.value

it('captures each quotation contact and restores it instead of the viewers current default', async () => {
  mount()
  await input('报价单署名', 'Vivian'); await input('WhatsApp 联系方式', '+183 5650 6953')
  const captured = exposed.capturePrices()
  expect(captured.contact).toEqual({ agent: 'Vivian', whatsapp: '+183 5650 6953' })
  await input('报价单署名', 'Next quotation'); await input('WhatsApp 联系方式', '+222')
  app.unmount(); document.body.innerHTML = ''
  mount([row()], undefined, { contact: captured.contact, quantities: captured.quantities, rows: [{ optionId: 'one', prices: captured.rows[0]!.prices }] }, true)
  await click('预览报价单')
  expect(render.mock.lastCall![0]).toMatchObject({ agent: 'Vivian', whatsapp: '+183 5650 6953' })
  authState.current = layoutUser('another-viewer'); await settle()
  expect(contactValue('报价单署名')).toBe('Vivian')
  expect(contactValue('WhatsApp 联系方式')).toBe('+183 5650 6953')
})

it('never inserts the viewers contact into a legacy record with no saved contact', async () => {
  mount(); await input('报价单署名', 'Viewer'); await input('WhatsApp 联系方式', '+999')
  app.unmount(); document.body.innerHTML = ''
  mount([row()], undefined, undefined, true)
  expect(contactValue('报价单署名')).toBe('Alex')
  expect(contactValue('WhatsApp 联系方式')).toBe('')
})

it('remembers the latest signature and contact for new products, remounts and rendered sheets', async () => {
  const state = mount()
  await input('报价单署名', 'Vivian')
  await input('WhatsApp 联系方式', '+183 5650 6953')
  state.contextKey = 'new-product'; await settle()
  expect(contactValue('报价单署名')).toBe('Vivian')
  expect(contactValue('WhatsApp 联系方式')).toBe('+183 5650 6953')
  app.unmount(); document.body.innerHTML = ''; mount(); await settle()
  await click('预览报价单')
  expect(render.mock.lastCall![0]).toMatchObject({ agent: 'Vivian', whatsapp: '+183 5650 6953' })
  await click('编辑报价单'); await input('WhatsApp 联系方式', '+86 123')
  app.unmount(); document.body.innerHTML = ''; mount(); await settle()
  expect(contactValue('WhatsApp 联系方式')).toBe('+86 123')
})

it('isolates contacts by signed-in account even when opening another salesperson record', async () => {
  const state = mount()
  await input('报价单署名', 'Alex') // Also preserve a saved name equal to the previous default.
  await input('WhatsApp 联系方式', '+111')
  state.salesperson = 'Record owner'; state.contextKey = 'other-record'; await settle()
  expect(contactValue('报价单署名')).toBe('Alex')
  authState.current = layoutUser('account-b'); await settle()
  expect(contactValue('WhatsApp 联系方式')).toBe('')
  await input('报价单署名', 'Bob'); await input('WhatsApp 联系方式', '+222')
  authState.current = layoutUser('account-a'); await settle()
  expect(contactValue('报价单署名')).toBe('Alex')
  expect(contactValue('WhatsApp 联系方式')).toBe('+111')
  authState.current = null; await settle()
  expect(contactValue('WhatsApp 联系方式')).toBe('')
})

it('keeps deliberate clearing and does not reset quote dates from contact preferences', async () => {
  const state = mount()
  await input('报价单署名', 'Vivian'); await input('WhatsApp 联系方式', '+111')
  await input('WhatsApp 联系方式', '')
  state.contextKey = 'new-product'; await settle()
  expect(contactValue('WhatsApp 联系方式')).toBe('')
  expect(contactValue('报价单署名')).toBe('Vivian')
})

it('continues editing with corrupt or unavailable preference storage and reports failed saving', async () => {
  localStorage.setItem('milano.quote-sheet-contact.v1:account-a', '{broken')
  mount()
  expect(contactValue('报价单署名')).toBe('Alex')
  vi.stubGlobal('localStorage', { getItem: () => null, clear: () => {}, setItem: () => { throw new Error('quota') } })
  await input('WhatsApp 联系方式', '+111')
  expect(contactValue('WhatsApp 联系方式')).toBe('+111')
  expect(document.body.textContent).toContain('署名或联系方式未能记住')
})

async function hideRowAt(index: number) {
  document.querySelector<HTMLButtonElement>(`[aria-label="隐藏第 ${index} 行"]`)!.click()
  await settle()
}

it('hides rows from preview and clipboard while preserving all saved prices and restoring edits', async () => {
  const state = mount([row('one', '4PX'), row('two')])
  const original = JSON.stringify(state.rows)
  await input('第 1 行第 1 列美元价格', '12.34')
  await input('第 1 行运输时效', '15-20 days')
  const prices = exposed.capturePrices()
  await hideRowAt(1)
  expect(document.querySelectorAll('.sheet-editor-scroll tbody tr')).toHaveLength(1)
  expect(document.querySelector('.sheet-row-tools')!.textContent).toContain('显示 1 行 · 已隐藏 1 行')
  expect(exposed.capturePrices()).toEqual({ ...prices, hiddenRowKeys: [prices.rows[0]!.key] })
  await click('预览报价单')
  expect(render.mock.lastCall![0].rows.map(row => row.provider)).toEqual(['Hua Hai'])
  expect(render.mock.lastCall![0].rows[0].number).toBe(1)
  await click('复制报价图片')
  expect(write.mock.lastCall![0][0].items['image/png']).toBe((await render.mock.results[0].value)[0].blob)
  await click('复制报价数据')
  expect(writeText.mock.lastCall![0]).toContain('Hua Hai')
  expect(writeText.mock.lastCall![0]).not.toContain('4PX')
  expect(writeText.mock.lastCall![0]).not.toContain('操作')
  await click('编辑报价单'); await click('恢复')
  expect(document.querySelector<HTMLInputElement>('[aria-label="第 1 行第 1 列美元价格"]')!.value).toBe('12.34')
  expect(document.querySelector<HTMLInputElement>('[aria-label="第 1 行运输时效"]')!.value).toBe('15-20 days')
  expect(JSON.stringify(state.rows)).toBe(original)
})

it('binds time edits to the visible route after hiding and reordering', async () => {
  const state = mount([{ ...row('one', '4PX'), eta: '2-3 days' }, { ...row('two'), eta: '7-9 days' }, row('three', 'SDH')])
  await hideRowAt(1)
  expect(document.querySelector<HTMLInputElement>('[aria-label="第 1 行运输时效"]')!.value).toBe('7-9 workingdays')
  await input('第 1 行运输时效', '10-12 days')
  state.rows.reverse(); await settle()
  expect(document.querySelectorAll('.sheet-editor-scroll tbody tr')).toHaveLength(2)
  await click('恢复全部')
  expect(document.querySelector<HTMLInputElement>('[aria-label="第 2 行运输时效"]')!.value).toBe('10-12 days')
  expect(document.querySelector<HTMLInputElement>('[aria-label="第 3 行运输时效"]')!.value).toBe('2-3 workingdays')
})

it('allows all rows to be hidden but disables empty exports until a row is restored', async () => {
  mount([row('one'), row('two')])
  await hideRowAt(1); await hideRowAt(1)
  expect(button('预览报价单').disabled).toBe(true)
  expect(button('复制报价数据').disabled).toBe(true)
  expect(document.body.textContent).toContain('所有行已隐藏')
  await exposed.preview(); const result = await exposed.copyData()
  expect(render).not.toHaveBeenCalled(); expect(writeText).not.toHaveBeenCalled()
  expect(result?.failed).toBe(true)
  await click('恢复全部')
  expect(button('预览报价单').disabled).toBe(false)
  expect(document.querySelectorAll('.sheet-editor-scroll tbody tr')).toHaveLength(2)
})

it('ignores hidden display validation for export but retains hidden price validation for saving', async () => {
  mount([row('one', '未知物流商'), row('two')])
  await input('第 1 行第 1 列美元价格', '1/0')
  await hideRowAt(1)
  await click('预览报价单'); expect(render).toHaveBeenCalledTimes(1)
  await click('复制报价数据'); expect(writeText).toHaveBeenCalledTimes(1)
  expect(() => exposed.capturePrices()).toThrow()
})

it('preserves hiding across recalculation but resets on product or account change and prunes removed routes', async () => {
  const state = mount([row('one'), row('two')]); state.resetKey = 'stable-product'; await settle()
  await hideRowAt(1)
  state.contextKey = 'new-calculation'; state.rows[1].quote1 = 20; await settle()
  expect(document.querySelectorAll('.sheet-editor-scroll tbody tr')).toHaveLength(1)
  state.rows = [row('two')]; await settle()
  state.rows.push(row('one')); await settle()
  expect(document.querySelectorAll('.sheet-editor-scroll tbody tr')).toHaveLength(2)
  await hideRowAt(1); state.resetKey = 'different-product'; await settle()
  expect(document.querySelectorAll('.sheet-editor-scroll tbody tr')).toHaveLength(2)
  await hideRowAt(1); authState.current = layoutUser('account-b'); await settle()
  expect(document.querySelectorAll('.sheet-editor-scroll tbody tr')).toHaveLength(2)
})

it('invalidates a pending preview when visibility changes and locks row actions during copying', async () => {
  mount([row('one'), row('two')])
  let finish!: (images: QuoteSheetImage[]) => void
  render.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  await click('预览报价单'); await hideRowAt(1); finish([png()]); await settle()
  expect(document.querySelector('.sheet-image-area')).toBeNull()
  let copied!: () => void
  writeText.mockImplementationOnce(() => new Promise<void>(resolve => { copied = resolve }))
  await click('复制报价数据')
  expect(button('隐藏').disabled).toBe(true)
  expect(button('恢复').disabled).toBe(true)
  expect(button('恢复全部').disabled).toBe(true)
  copied(); await settle()
  expect(button('隐藏').disabled).toBe(false)
})

it('switches the Country header, preview and copied data together without changing source rows or saved prices', async () => {
  const state = mount([{ ...row(), country: 'GB' }, { ...row('two'), country: 'NL' }])
  const original = JSON.stringify(state.rows)
  const prices = exposed.capturePrices()
  expect(document.querySelector('[data-group="country"] [aria-label="国家显示方式"]')).not.toBeNull()
  expect(button('全称').getAttribute('aria-pressed')).toBe('true')
  expect(document.querySelector<HTMLInputElement>('.sheet-country')!.value).toBe('United Kingdom')
  await click('二字码')
  expect(button('二字码').getAttribute('aria-pressed')).toBe('true')
  expect([...document.querySelectorAll<HTMLInputElement>('.sheet-country')].map(input => input.value)).toEqual(['GB', 'NL'])
  await click('预览报价单'); await click('复制报价数据')
  expect(render.mock.lastCall![0].rows.map(row => row.country)).toEqual(['GB', 'NL'])
  expect(writeText.mock.lastCall![0]).toContain('\tGB\t')
  expect(writeText.mock.lastCall![0]).toContain('\tNL\t')
  await click('编辑报价单'); await click('全称'); await click('预览报价单'); await click('复制报价数据')
  expect(render.mock.lastCall![0].rows.map(row => row.country)).toEqual(['United Kingdom', 'Netherlands'])
  expect(writeText.mock.lastCall![0]).toContain('\tUnited Kingdom\t')
  expect(exposed.capturePrices()).toEqual(prices)
  expect(JSON.stringify(state.rows)).toBe(original)
})

it('converts manual country edits on toggling and keeps the format across recalculation but resets for a new product', async () => {
  const state = mount()
  state.resetKey = 'sku-one'; await settle()
  await input('第 1 行国家', 'Netherlands')
  await click('二字码')
  expect(document.querySelector<HTMLInputElement>('.sheet-country')!.value).toBe('NL')
  state.contextKey = 'price-changed'; await settle()
  expect(button('二字码').getAttribute('aria-pressed')).toBe('true')
  await click('全称')
  expect(document.querySelector<HTMLInputElement>('.sheet-country')!.value).toBe('Netherlands')
  await click('二字码'); state.resetKey = 'sku-two'; await settle()
  expect(button('全称').getAttribute('aria-pressed')).toBe('true')
  expect(document.querySelector<HTMLInputElement>('.sheet-country')!.value).toBe('United States')
})

it('invalidates a pending full-name image on format change and locks both choices during clipboard writes', async () => {
  let finish!: (images: QuoteSheetImage[]) => void
  render.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  mount(); await click('预览报价单'); await click('二字码')
  finish([png()]); await settle()
  expect(document.querySelector('.sheet-image-scroll img')).toBeNull()
  expect(button('复制报价图片').disabled).toBe(true)
  let copied!: () => void
  writeText.mockImplementationOnce(() => new Promise<void>(resolve => { copied = resolve }))
  await click('复制报价数据')
  expect(button('全称').disabled).toBe(true)
  expect(button('二字码').disabled).toBe(true)
  copied(); await settle()
  expect(button('全称').disabled).toBe(false)
})

it.each([false, true])('keeps arithmetic preview, clipboard and saved prices consistent (bundle=%s)', async bundle => {
  const state = mount([row('one'), row('two')]); state.bundle = bundle; await settle()
  const original = JSON.stringify(state.rows)
  await input('第 1 行第 1 列美元价格', '42.6/0.95')
  // Saving or copying before blur must still capture the computed number, never the expression or old price.
  expect(exposed.capturePrices().rows[0]!.prices[0]).toBe(44.84)
  expect(exposed.capturePrices().rows[0]!.systemPrices[0]).toBe(10.8)
  await click('复制报价数据'); expect(writeText.mock.lastCall![0]).toContain('$44.84')
  expect(writeText.mock.lastCall![0]).not.toContain('42.6/0.95')
  const field = document.querySelector<HTMLInputElement>('[aria-label="第 1 行第 1 列美元价格"]')!
  field.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter',bubbles:true})); await settle()
  expect(field.value).toBe('44.84')
  field.dispatchEvent(new FocusEvent('blur')); await settle()
  expect(field.value).toBe('44.84')
  state.rows.reverse(); await settle()
  expect(exposed.capturePrices().rows[1]!.prices[0]).toBe(44.84)
  await input('第 2 行第 2 列美元价格', '(40+2)*2')
  document.querySelector<HTMLInputElement>('[aria-label="第 2 行第 2 列美元价格"]')!.dispatchEvent(new FocusEvent('blur')); await settle()
  await click('预览报价单')
  expect(render.mock.lastCall![0].rows[1]!.prices.slice(0,2)).toEqual([44.84,84])
  expect(JSON.stringify([...state.rows].reverse())).toBe(original)
})

it.each(['42.6/0','42.6+','(42.6+2','2-3','999999999.99*2'])('blocks invalid arithmetic %s in preview, copy and capture', async expression => {
  mount(); await input('第 1 行第 1 列美元价格',expression)
  const field = document.querySelector<HTMLInputElement>('[aria-label="第 1 行第 1 列美元价格"]')!
  field.dispatchEvent(new FocusEvent('blur')); await settle()
  expect(field.value).toBe(expression)
  expect(field.getAttribute('aria-invalid')).toBe('true')
  expect(()=>exposed.capturePrices()).toThrow()
  await click('预览报价单'); expect(render).not.toHaveBeenCalled()
  await click('复制报价数据'); expect(writeText).not.toHaveBeenCalled()
  await input('第 1 行第 1 列美元价格','42.6+2')
  expect(exposed.capturePrices().rows[0]!.prices[0]).toBe(44.6)
  expect(field.getAttribute('aria-invalid')).toBe('false')
})

it('restores numeric snapshots and clears arithmetic on product reset without carrying stale edits to new quantity columns', async()=>{
  const state=mount([row()], undefined, {quantities:[1,2],rows:[{optionId:'one',prices:[44.84,84]}]})
  expect(exposed.capturePrices().rows[0]!.prices).toEqual([44.84,84])
  await input('第 1 行第 1 列美元价格','44.84+1')
  await input('第 1 个价格列数量','8')
  await input('第 1 个价格列数量','1')
  expect(exposed.capturePrices().rows[0]!.prices[0]).toBe(10.8)
  await input('第 1 行第 1 列美元价格','42.6+2')
  state.initialQuote=undefined;state.contextKey='new';await settle()
  expect(exposed.capturePrices().rows[0]!.prices[0]).toBe(10.8)
})

it('previews the formerly blocked carriers and copies the exact preview blob and all 10 table columns', async () => {
  const state = mount([row(), row('two', '闪电猴')]); const original = JSON.stringify(state.rows)
  await click('预览报价单')
  expect(document.querySelector('img')?.src).toBe('blob:quote')
  expect(render.mock.calls[0][0].rows.map(row => row.provider)).toEqual(['Hua Hai', 'SDH Express'])
  await click('复制报价图片')
  expect(write.mock.calls[0][0][0].items['image/png']).toBe((await render.mock.results[0].value)[0].blob)
  await click('复制报价数据')
  const cells = writeText.mock.calls[0][0].split('\r\n').map((line: string) => line.split('\t'))
  expect(cells).toHaveLength(3); expect(cells.every((line: string[]) => line.length === 10)).toBe(true)
  expect(cells[2]).toEqual(['2', 'SKU-001', '$10.80', '$16.35', '$21.90', '$30.85', 'United States', 'SDH Express', '8-12 workingdays', '1-2 workingdays'])
  expect(writeText.mock.calls[0][0]).not.toMatch(/内部|全国统一/)
  expect(JSON.stringify(state.rows)).toBe(original)
})

it('previews and copies a saved missing-ETA route, supports a local supplement and restores the unknown value', async () => {
  const state = mount([{ ...row('legacy', '极通环球'), eta: '该物流暂无时效说明' }])
  expect(document.querySelector<HTMLInputElement>('[aria-label="第 1 行运输时效"]')!.value).toBe('')
  await click('预览报价单'); expect(render.mock.lastCall![0].rows[0]!.shippingTime).toBe('—')
  await click('复制报价数据'); expect(writeText.mock.lastCall![0]).toContain('JITO\t—\t1-2 workingdays')
  await click('编辑报价单'); await input('第 1 行运输时效', '10-15 days')
  await click('复制报价数据'); expect(writeText.mock.lastCall![0]).toContain('JITO\t10-15 workingdays')
  await click('恢复渠道时效'); await click('复制报价数据')
  expect(writeText.mock.lastCall![0]).toContain('JITO\t—\t1-2 workingdays')
  expect(state.rows[0]!.eta).toBe('该物流暂无时效说明')
})

it('shows errors above a long table and allows one English-name supplement for all matching routes', async () => {
  mount([row('one', '新物流'), row('two', '新物流')])
  await click('预览报价单')
  expect(render).not.toHaveBeenCalled()
  const alert = document.querySelector('[role="alert"]')!
  expect(alert.textContent).toContain('英文名补填区')
  expect(alert.compareDocumentPosition(document.querySelector('.sheet-editor')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(document.querySelectorAll('[aria-label="新物流英文名"]')).toHaveLength(1)
  await input('新物流英文名', 'New Logistics')
  await click('预览报价单')
  expect(render.mock.calls[0][0].rows.map(row => row.provider)).toEqual(['New Logistics', 'New Logistics'])
  await click('复制报价数据'); expect(writeText.mock.calls[0][0].match(/New Logistics/g)).toHaveLength(2)
})

it('table copying is independent of the image-only name/date requirement', async () => {
  mount(); await input('报价单署名', '')
  await click('预览报价单'); expect(render).not.toHaveBeenCalled()
  await click('复制报价数据'); expect(writeText).toHaveBeenCalledTimes(1)
  expect(document.querySelector('[role="status"]')?.textContent).toContain('已复制 1 条')
})

it('invalidates previews and binds time edits to route identity through reorder and context changes', async () => {
  const state = mount([row('one'), row('two')])
  await input('第 1 行运输时效', '15-20 days'); await click('预览报价单')
  state.rows.reverse(); await settle()
  expect(revoke).toHaveBeenCalledWith('blob:quote'); expect(button('复制报价图片').disabled).toBe(true)
  await click('复制报价数据')
  expect(writeText.mock.lastCall![0].split('\r\n')[2]).toContain('15-20 workingdays')
  state.contextKey = 'product-2'; await settle(); await click('复制报价数据')
  expect(writeText.mock.lastCall![0]).not.toContain('15-20 workingdays')
  state.rows[0]!.eta = '3～5 天'; state.rows[0]!.quote1 = 99.5; await settle(); await click('复制报价数据')
  expect(writeText.mock.lastCall![0]).toContain('3-5 workingdays\t1-2 workingdays'); expect(writeText.mock.lastCall![0]).toContain('$99.50')
})

it('clears temporary provider names when switching quotation context', async () => {
  const state = mount([row('one', '新物流')])
  await input('新物流英文名', 'New Logistics'); await click('复制报价数据')
  state.contextKey = 'another'; await settle(); await click('复制报价数据')
  expect(writeText).toHaveBeenCalledTimes(1)
  expect(document.querySelector('[role="alert"]')?.textContent).toContain('英文名称')
})

it('ignores an old render completing after a newer calculation', async () => {
  let resolve!: (images: QuoteSheetImage[]) => void
  render.mockReturnValueOnce(new Promise(done => { resolve = done }))
  const state = mount(); await click('预览报价单')
  state.rows[0]!.quote1 = 100; await settle()
  resolve([png()]); await settle()
  expect(document.querySelector('img')).toBeNull()
  expect(button('复制报价图片').disabled).toBe(true)
  await click('预览报价单'); expect(render.mock.lastCall![0].rows[0]!.prices[0]).toBe(100)
})

it('blocks pending calculations and warns when clipboard completion belongs to old data', async () => {
  let resolve!: () => void
  const state = mount(); state.sourcePending = true; await settle()
  expect(button('预览报价单').disabled).toBe(true); expect(button('复制报价数据').disabled).toBe(true)
  state.sourcePending = false; await settle()
  writeText.mockImplementationOnce(() => new Promise<void>(done => { resolve = done }))
  await click('复制报价数据'); state.rows[0]!.quote1 = 222; await settle()
  resolve(); await settle()
  expect(document.querySelector('[role="alert"]')?.textContent).toContain('重新复制最新数据')
})

it('retains the image on clipboard denial and never reports a successful copy', async () => {
  mount(); await click('预览报价单')
  write.mockRejectedValue(new Error('NotAllowedError')); await click('复制报价图片')
  expect(document.querySelector('img')).not.toBeNull()
  expect(document.querySelector('[role="alert"]')?.textContent).toContain('图片未复制成功')
  expect(document.querySelector('.sheet-message')?.textContent).not.toContain('已复制')
  writeText.mockRejectedValue(new Error('NotAllowedError')); await click('复制报价数据')
  expect(document.querySelector('[role="alert"]')?.textContent).toContain('未复制成功')
})

it('copies the selected page and releases every image on leaving the component', async () => {
  const pages = [png(1, 24), png(25, 49)]; render.mockResolvedValueOnce(pages)
  mount(); await click('预览报价单'); await click('下一张'); await click('复制当前图片')
  expect(write.mock.calls[0][0][0].items['image/png']).toBe(pages[1]!.blob)
  expect(document.querySelector('.sheet-message')?.textContent).toContain('第 2 张')
  app.unmount(); expect(revoke).toHaveBeenCalledTimes(2)
})


it('adds inline quantity columns, calculates by original route identity and copies exactly the edited visible table', async () => {
  const calc = vi.fn((route: QuoteSheetSourceRow, quantity: number) => route.channelKey === 'two' ? null : quantity * 3 + 1.25)
  const state = mount([row('one'), row('two')], calc)
  const original = JSON.stringify(state.rows)
  await click('＋ 新增列')
  await click('复制报价数据'); expect(writeText).not.toHaveBeenCalled()
  await input('第 5 个价格列数量', '8')
  expect(calc).toHaveBeenCalledWith(expect.objectContaining({ channelKey: 'one', country: '美国' }), 8)
  await input('第 1 行国家', 'CA'); await input('第 1 行物流商', 'Custom Carrier')
  await input('第 1 行处理时间', '3-4 days'); await input('第 1 行第 5 列美元价格', '27.50')
  await click('预览报价单'); await click('复制报价数据')
  const snapshot = render.mock.lastCall![0]
  expect(snapshot.quantityLabels).toEqual(['1 pc', '2 pcs', '3 pcs', '5 pcs', '8 pcs'])
  expect(snapshot.rows[0]).toMatchObject({ country: 'Canada', provider: 'Custom Carrier', processingTime: '3-4 workingdays', prices: [10.8,16.35,21.9,30.85,27.5] })
  expect(snapshot.rows[1].prices[4]).toBeNull()
  const cells = writeText.mock.lastCall![0].split('\r\n').map((line: string) => line.split('\t'))
  expect(cells.every((cells: string[]) => cells.length === 11)).toBe(true)
  expect(cells[1][6]).toBe('$27.50'); expect(cells[2][6]).toBe('—')
  expect(JSON.stringify(state.rows)).toBe(original)
})

it('enforces ten columns and invalid quantities; deleted/reused quantities cannot inherit stale manual prices', async () => {
  mount([row()], (_route, quantity) => quantity + 0.25)
  for (const quantity of [4,6,7,8,9,10]) {
    await click('＋ 新增列')
    const count = document.querySelectorAll('.sheet-quantity').length
    await input(`第 ${count} 个价格列数量`, String(quantity))
  }
  expect(button('＋ 新增列').disabled).toBe(true)
  await click('复制报价数据'); expect(writeText.mock.lastCall![0].split('\r\n')[0].split('\t')).toHaveLength(16)
  for (const invalid of ['', '0', '-1', '1.5', '9007199254740992', '3']) {
    await input('第 10 个价格列数量', invalid)
    await click('预览报价单'); expect(render).not.toHaveBeenCalled()
  }
  await input('第 10 个价格列数量', '20')
  await input('第 1 行第 10 列美元价格', '199.99')
  document.querySelector<HTMLButtonElement>('[aria-label="删除第 10 个价格列"]')!.click(); await settle()
  await click('＋ 新增列'); await input('第 10 个价格列数量', '20')
  await click('预览报价单'); expect(render.mock.lastCall![0].rows[0].prices[9]).toBe(20.25)
})

it('isolates rapid quantity changes from a pending image and resets added columns when product changes', async () => {
  const state = mount([row()], (_route, quantity) => quantity + .5)
  await click('＋ 新增列'); await input('第 5 个价格列数量', '8')
  let finish!: (images: QuoteSheetImage[]) => void
  render.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  await click('预览报价单')
  for (const quantity of ['10','20','6','9']) await input('第 5 个价格列数量', quantity)
  finish([png()]); await settle(); expect(document.querySelector('img')).toBeNull()
  await click('预览报价单'); expect(render.mock.lastCall![0].rows[0].prices[4]).toBe(9.5)
  state.contextKey = 'another-product'; await settle()
  expect(document.querySelectorAll('.sheet-quantity')).toHaveLength(4)
  expect(button('复制报价图片').disabled).toBe(true)
})

it('preserves row price edits on reorder but clears them when source prices recalculate', async () => {
  const state = mount([row('one'), row('two')])
  await input('第 1 行第 1 列美元价格', '77.70')
  state.rows.reverse(); await settle(); await click('复制报价数据')
  expect(writeText.mock.lastCall![0].split('\r\n')[2]).toContain('$77.70')
  state.rows[1].quote1 = 12.50; await settle(); await click('复制报价数据')
  expect(writeText.mock.lastCall![0]).not.toContain('$77.70')
  expect(writeText.mock.lastCall![0].split('\r\n')[2]).toContain('$12.50')
})

it('historical records never synthesize prices for unsaved quantities and permit explicit manual quotes', async () => {
  mount(); await click('＋ 新增列'); await input('第 5 个价格列数量', '50')
  await click('预览报价单'); expect(render.mock.lastCall![0].rows[0].prices[4]).toBeNull()
  await click('编辑报价单'); await input('第 1 行第 5 列美元价格', '150.25')
  await click('复制报价数据'); expect(writeText.mock.lastCall![0]).toContain('$150.25')
})


it('keeps selected quantities when pricing context changes but clears prices; a different product clears all edits', async () => {
  const state = mount([row()], (_route, quantity) => quantity + 1)
  state.resetKey = 'sku-one'; await settle()
  await click('＋ 新增列'); await input('第 5 个价格列数量', '8')
  await input('第 1 行第 5 列美元价格', '99.99')
  state.contextKey = 'grade-changed'; await settle(); await click('复制报价数据')
  expect(document.querySelectorAll('.sheet-quantity')).toHaveLength(5)
  expect(writeText.mock.lastCall![0]).toContain('$9.00'); expect(writeText.mock.lastCall![0]).not.toContain('$99.99')
  state.resetKey = 'sku-two'; await settle()
  expect(document.querySelectorAll('.sheet-quantity')).toHaveLength(4)
})


it('retains a historical Custom price without inventing its quantity; entering a new quantity needs a new price', async () => {
  const state = mount(); state.customQuantity = 0; state.contextKey = 'legacy'; await settle()
  expect(document.querySelectorAll('.sheet-quantity')).toHaveLength(4)
  await click('复制报价数据'); expect(writeText.mock.lastCall![0]).toContain('Custom (USD)')
  expect(writeText.mock.lastCall![0]).toContain('$30.85')
  await input('第 4 个价格列数量', '8'); await click('复制报价数据')
  expect(writeText.mock.lastCall![0]).toContain('8 pcs (USD)')
  expect(writeText.mock.lastCall![0]).not.toContain('$30.85')
})


it('drag sorting moves the complete quantity column with manual prices into both PNG and TSV', async () => {
  const state = mount([row(),row('two')], (_route,quantity)=>quantity+0.5)
  const original=JSON.stringify(state.rows)
  await click('＋ 新增列');await input('第 5 个价格列数量','8')
  await input('第 1 行第 5 列美元价格','77.75')
  const handles=document.querySelectorAll<HTMLButtonElement>('.sheet-drag')
  handles[4].dispatchEvent(new Event('dragstart',{bubbles:true}))
  document.querySelectorAll('.sheet-quantity')[0].dispatchEvent(new Event('drop',{bubbles:true,cancelable:true}))
  await settle();await click('预览报价单');await click('复制报价数据')
  expect(render.mock.lastCall![0].quantityLabels).toEqual(['8 pcs','1 pc','2 pcs','3 pcs','5 pcs'])
  expect(render.mock.lastCall![0].rows[0].prices).toEqual([77.75,10.8,16.35,21.9,30.85])
  expect(render.mock.lastCall![0].rows[1].prices[0]).toBe(8.5)
  expect(writeText.mock.lastCall![0].split('\r\n')[1].split('\t')[2]).toBe('$77.75')
  expect(JSON.stringify(state.rows)).toBe(original)
})

it('keyboard ordering supports boundaries and preserves focus, then editing still addresses the moved quantity',async()=>{
  mount()
  const handle=document.querySelector<HTMLButtonElement>('.sheet-drag')!
  handle.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowLeft',bubbles:true}));await settle()
  expect(document.querySelector<HTMLInputElement>('.sheet-quantity input')!.value).toBe('1')
  handle.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true}));await settle()
  expect(document.activeElement?.getAttribute('aria-label')).toBe('第 2 个价格列排序，左右键移动')
  await input('第 1 行第 2 列美元价格','66.66');await click('复制报价数据')
  expect(writeText.mock.lastCall![0].split('\r\n')[0].split('\t').slice(2, -4)).toEqual(['2 pcs (USD)','1 pc (USD)','3 pcs (USD)','5 pcs (USD)'])
  expect(writeText.mock.lastCall![0].split('\r\n')[1].split('\t').slice(2, -4)).toEqual(['$16.35','$66.66','$21.90','$30.85'])
})

it.each(['edit-away', 'remove'])('preserves the original manual price when a duplicate quantity is corrected by %s', async action => {
  mount()
  await input('第 1 行第 2 列美元价格', '88.88')
  await click('＋ 新增列'); await input('第 5 个价格列数量', '2')
  await click('复制报价数据'); expect(writeText).not.toHaveBeenCalled()
  if (action === 'edit-away') await input('第 5 个价格列数量', '8')
  else { document.querySelector<HTMLButtonElement>('[aria-label="删除第 5 个价格列"]')!.click(); await settle() }
  await click('复制报价数据')
  expect(writeText.mock.lastCall![0].split('\r\n')[1].split('\t')[3]).toBe('$88.88')
})

it('keeps duplicate quantity price inputs disabled so temporary duplicates cannot edit another column', async () => {
  mount(); await click('＋ 新增列'); await input('第 5 个价格列数量', '2')
  expect(document.querySelector<HTMLInputElement>('[aria-label="第 1 行第 5 列美元价格"]')!.disabled).toBe(true)
})

it('rejects a burst of repeated previews and only accepts the newest of reversed render completions', async () => {
  const jobs: Array<{ finish: (value: QuoteSheetImage[]) => void; reject: (error: Error) => void }> = []
  render.mockImplementation(() => new Promise((finish, reject) => jobs.push({ finish, reject })))
  mount()
  for (let i = 0; i < 20; i++) button('预览报价单').click()
  await settle(); expect(jobs).toHaveLength(1)
  await input('第 1 行运输时效', '10-20 days'); await click('预览报价单')
  await input('第 1 行运输时效', '2-3 days'); await click('预览报价单')
  jobs[2].finish([png()]); await settle()
  jobs[1].reject(new Error('old failure')); jobs[0].finish([png()]); await settle()
  expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
  expect(document.querySelector('[role="alert"]')).toBeNull()
  await click('复制报价数据'); expect(writeText.mock.lastCall![0]).toContain('2-3 workingdays')
})

it('does not allocate image URLs when an unmounted render finishes', async () => {
  let finish!: (images: QuoteSheetImage[]) => void
  render.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  mount(); await click('预览报价单'); app.unmount()
  finish([png()]); await settle(); expect(URL.createObjectURL).not.toHaveBeenCalled()
})

it('revokes partially allocated pages if object URL creation fails and allows a clean retry', async () => {
  render.mockResolvedValueOnce([png(1,24), png(25,48)])
  vi.mocked(URL.createObjectURL).mockReturnValueOnce('blob:first').mockImplementationOnce(() => { throw new Error('allocation failed') })
  mount(); await click('预览报价单')
  expect(revoke).toHaveBeenCalledWith('blob:first')
  expect(document.querySelector('img')).toBeNull()
  await click('预览报价单'); expect(document.querySelector('img')).not.toBeNull()
})

it.each([17, 43, 101])('checks every copied cell through 40 interleaved edits, moves, row reversals and recalculations (seed %i)', async seed => {
  const state = mount([row('one'), row('two')], (route, q) => q + (route.channelKey === 'one' ? 0.25 : 0.75))
  state.resetKey = 'same-product'; await settle()
  await click('＋ 新增列'); await input('第 5 个价格列数量', '8')
  const order = [1,2,3,5,8]
  const manual = new Map<string, number>()
  let random = seed
  for (let step = 0; step < 40; step++) {
    random = (random * 1664525 + 1013904223) >>> 0
    const column = random % order.length
    switch (step % 5) {
      case 0: {
        const price = 50 + step / 10
        manual.set(`${state.rows[0].channelKey}:${order[column]}`, price)
        await input(`第 1 行第 ${column + 1} 列美元价格`, price.toFixed(2)); break
      }
      case 1: {
        const to = (column + 1) % order.length
        document.querySelectorAll('.sheet-drag')[column].dispatchEvent(new Event('dragstart', { bubbles: true }))
        document.querySelectorAll('.sheet-quantity')[to].dispatchEvent(new Event('drop', { bubbles: true, cancelable: true }))
        const [value] = order.splice(column, 1); order.splice(to, 0, value); await settle(); break
      }
      case 2: state.rows.reverse(); await settle(); break
      case 3: {
        const old = order[column], quantity = 20 + step
        order[column] = quantity
        for (const source of state.rows) manual.delete(`${source.channelKey}:${old}`)
        await input(`第 ${column + 1} 个价格列数量`, String(quantity)); break
      }
      case 4: state.contextKey = `recalculation-${step}`; manual.clear(); await settle(); break
    }
    const sourceBeforeCopy = JSON.stringify(state.rows)
    await click('复制报价数据')
    const cells = writeText.mock.lastCall![0].split('\r\n').map((line: string) => line.split('\t'))
    expect(cells[0].slice(2, -4)).toEqual(order.map(q=>`${q} ${q===1?'pc':'pcs'} (USD)`))
    state.rows.forEach((source, index) => {
      const saved = new Map([[1,source.quote1],[2,source.quote2],[3,source.quote3],[5,source.quoteCustom]])
      expect(cells[index+1].slice(2, -4)).toEqual(order.map(q=>`$${(manual.get(`${source.channelKey}:${q}`) ?? saved.get(q) ?? q+(source.channelKey==='one'?0.25:0.75)).toFixed(2)}`))
    })
    expect(JSON.stringify(state.rows)).toBe(sourceBeforeCopy)
  }
})

it('captures immutable original and edited customer prices including added quantities for record creation',async()=>{
  mount([row()],(_row,q)=>q+1)
  await click('＋ 新增列');await input('第 5 个价格列数量','8');await input('第 1 行第 5 列美元价格','8.80')
  await input('第 1 行第 2 列美元价格','15.50')
  const captured=exposed.capturePrices()
  expect(captured.quantities).toEqual([1,2,3,5,8])
  expect(captured.rows[0].systemPrices).toEqual([10.8,16.35,21.9,30.85,9])
  expect(captured.rows[0].prices).toEqual([10.8,15.5,21.9,30.85,8.8])
})

it('keeps record amount capture independent of display translations but rejects invalid price columns',async()=>{
  mount([{...row(),carrier:'未配置英文的物流商'}])
  expect(exposed.capturePrices().rows[0].prices[0]).toBe(10.8)
  await input('第 1 行第 1 列美元价格','1.234')
  expect(()=>exposed.capturePrices()).toThrow('两位小数')
  await input('第 1 行第 1 列美元价格','1.20')
  await click('＋ 新增列')
  expect(()=>exposed.capturePrices()).toThrow('正整数')
})

it('does not save a manual new-quantity price with a silently failed system baseline',async()=>{
  mount([row()],()=>{throw new Error('calculation failed')})
  await click('＋ 新增列');await input('第 5 个价格列数量','8');await input('第 1 行第 5 列美元价格','8.80')
  expect(()=>exposed.capturePrices()).toThrow('计算失败')
})

it('loads persisted customer quantity order and prices into preview and clipboard, then reloads after a record revision',async()=>{
  const state=mount([row()],undefined,{quantities:[4,2,1],rows:[{optionId:'one',prices:[4.6,2.7,1.8]}]})
  await click('预览报价单');expect(render.mock.lastCall![0].quantityLabels).toEqual(['4 pcs','2 pcs','1 pc'])
  expect(render.mock.lastCall![0].rows[0].prices).toEqual([4.6,2.7,1.8])
  await click('复制报价数据');expect(writeText.mock.lastCall![0]).toContain('$4.60\t$2.70\t$1.80')
  state.initialQuote={quantities:[4,2,1],rows:[{optionId:'one',prices:[4.6,2.6,1.8]}]};state.contextKey='record-v2';await settle()
  await click('复制报价数据');expect(writeText.mock.lastCall![0]).toContain('$4.60\t$2.60\t$1.80')
})


it('hides each descriptive column in the editor, PNG model and clipboard and restores local edits without changing prices', async () => {
  const state = mount(); state.bundle = true; state.skus = ['SKU-A', 'SKU-B']; await settle()
  await input('第 1 行国家', 'Canada')
  await input('第 1 行第 1 列美元价格', '66.66')
  const before = exposed.capturePrices()
  for (const name of ['国家', '物流商', '运输时效', '处理时间']) {
    document.querySelector<HTMLInputElement>(`[aria-label="显示${name}列"]`)!.click(); await settle()
  }
  expect(document.querySelector('[aria-label="第 1 行国家"]')).toBeNull()
  await click('预览报价单')
  expect(render.mock.lastCall![0].hiddenColumns).toEqual(['country', 'provider', 'shippingTime', 'processingTime'])
  expect(render.mock.lastCall![0].rows[0].sku).toBe('SKU-A+SKU-B')
  expect([...document.querySelectorAll('.sheet-accessible th')].map(cell => cell.textContent)).toEqual(['No.', 'SKU', '1 set (USD)', '2 sets (USD)', '3 sets (USD)', '5 sets (USD)'])
  await click('复制报价数据')
  expect(writeText.mock.lastCall![0].split('\r\n')[1]).toBe('1\tSKU-A+SKU-B\t$66.66\t$16.35\t$21.90\t$30.85')
  expect(writeText.mock.lastCall![0]).not.toMatch(/Country|Canada|Hua Hai|days/)
  expect(exposed.capturePrices()).toEqual(before)
  document.querySelector<HTMLInputElement>('[aria-label="显示国家列"]')!.click(); await settle()
  expect(document.querySelector('img')).toBeNull()
  expect(document.querySelector<HTMLInputElement>('[aria-label="第 1 行国家"]')!.value).toBe('Canada')
  state.skus = ['SKU-C']; await settle(); expect(document.querySelector('.sheet-sku')!.textContent).toBe('SKU-C')
  state.resetKey = 'another-product'; await settle()
  expect([...document.querySelectorAll<HTMLInputElement>('.sheet-visibility input')].every(input => input.checked)).toBe(true)
})

it('unlocks the quote editor and shows a truthful timeout when the native clipboard never returns', async () => {
  mount(); vi.useFakeTimers()
  let reject!: (error: Error) => void
  writeText.mockImplementationOnce(() => new Promise<void>((_resolve, fail) => { reject = fail }))
  try {
    button('复制报价数据').click(); await settle()
    expect(button('复制报价数据').disabled).toBe(true)
    await vi.advanceTimersByTimeAsync(8000); await settle()
    expect(button('复制报价数据').disabled).toBe(false)
    expect(document.querySelector<HTMLInputElement>('[aria-label="第 1 行国家"]')!.disabled).toBe(false)
    expect(document.querySelector('[role="alert"]')?.textContent).toContain('复制超时')
    reject(new Error('late denial')); await settle()
    expect(document.querySelector('[role="alert"]')?.textContent).toContain('复制超时')
  } finally { vi.useRealTimers() }
})

function groupKeys() { return [...document.querySelectorAll<HTMLElement>('.sheet-group')].map(header => header.dataset.group) }
async function dragGroup(from: string, to: string) {
  document.querySelector(`[data-group-handle="${from}"]`)!.dispatchEvent(new Event('dragstart', { bubbles: true }))
  document.querySelector(`[data-group="${to}"]`)!.dispatchEvent(new Event('drop', { bubbles: true, cancelable: true }))
  await settle()
}
it('moves the whole price group across both ends without changing captured prices, and aligns TSV and accessible preview', async () => {
  const state = mount([row(), row('two')])
  await input('第 1 行第 2 列美元价格', '42.6+2')
  const captured = exposed.capturePrices(), source = JSON.stringify(state.rows)
  expect(groupKeys()).toEqual(['number','sku','prices','country','provider','shippingTime','processingTime'])
  for (const target of ['number', 'processingTime', 'sku']) {
    await dragGroup('prices', target)
    const keys = groupKeys()
    expect(exposed.capturePrices()).toEqual(captured)
    expect(document.querySelector('.sheet-price-group')?.getAttribute('colspan')).toBe('4')
    await click('预览报价单'); await click('复制报价数据')
    const table = writeText.mock.lastCall![0].split('\r\n').map((line: string) => line.split('\t'))
    const priceAt = table[0].indexOf('1 pc (USD)')
    expect(priceAt).toBe(keys.indexOf('prices'))
    expect(table[0].slice(priceAt, priceAt + 4)).toEqual(['1 pc (USD)','2 pcs (USD)','3 pcs (USD)','5 pcs (USD)'])
    expect(table[1].slice(priceAt, priceAt + 4)).toEqual(['$10.80','$44.60','$21.90','$30.85'])
    expect(table[2][table[0].indexOf('Logistics Provider')]).toBe('Hua Hai')
    const accessible = [...document.querySelectorAll('.sheet-accessible tr')].map(tr => [...tr.children].map(td=>td.textContent))
    expect(accessible).toEqual(table)
    await click('编辑报价单')
  }
  expect(JSON.stringify(state.rows)).toBe(source)
})
it('keeps a moved hidden column in place when shown again, supports keyboard sorting and preserves layout across products', async () => {
  const state = mount()
  await dragGroup('country', 'number')
  const toggle = document.querySelector<HTMLInputElement>('[aria-label="显示国家列"]')!
  toggle.click(); await settle()
  expect(groupKeys()).not.toContain('country')
  toggle.click(); await settle()
  expect(groupKeys()[0]).toBe('country')
  document.querySelector('[data-group-handle="sku"]')!.dispatchEvent(new KeyboardEvent('keydown', { key:'ArrowLeft', bubbles:true }))
  await settle()
  expect(groupKeys().slice(0,3)).toEqual(['country','sku','number'])
  expect(document.activeElement?.getAttribute('data-group-handle')).toBe('sku')
  await input('第 1 行国家', 'CA'); await click('复制报价数据')
  expect(writeText.mock.lastCall![0].split('\r\n')[1].split('\t')[0]).toBe('Canada')
  state.contextKey = 'next-product'; await settle()
  expect(groupKeys()).toEqual(['country','sku','number','prices','provider','shippingTime','processingTime'])
  expect(document.querySelector<HTMLInputElement>('.sheet-country')!.value).toBe('United States')
})
it('restores layout after remount and isolates layouts by signed-in account instead of salesperson', async () => {
  mount()
  await dragGroup('country', 'prices')
  const accountAOrder = groupKeys()
  app.unmount(); document.body.innerHTML = ''
  const state = mount()
  expect(groupKeys()).toEqual(accountAOrder)
  state.salesperson = 'Another record owner'; await settle()
  expect(groupKeys()).toEqual(accountAOrder)
  authState.current = layoutUser('account-b'); await settle()
  expect(groupKeys()).toEqual(['number','sku','prices','country','provider','shippingTime','processingTime'])
  await dragGroup('provider', 'number')
  const accountBOrder = groupKeys()
  authState.current = null; await settle()
  expect(groupKeys()).toEqual(['number','sku','prices','country','provider','shippingTime','processingTime'])
  authState.current = layoutUser('account-a'); await settle()
  expect(groupKeys()).toEqual(accountAOrder)
  authState.current = layoutUser('account-b'); await settle()
  expect(groupKeys()).toEqual(accountBOrder)
  await click('恢复默认列顺序')
  app.unmount(); document.body.innerHTML = ''; mount()
  expect(groupKeys()).toEqual(['number','sku','prices','country','provider','shippingTime','processingTime'])
  authState.current = layoutUser('account-a'); await settle()
  expect(groupKeys()).toEqual(accountAOrder)
})
it('falls back from invalid stored layout and reports unavailable storage without blocking rearrangement', async () => {
  localStorage.setItem('milano.quote-sheet-column-order.v1:account-a', '{broken')
  mount()
  const prices = exposed.capturePrices()
  expect(groupKeys()).toEqual(['number','sku','prices','country','provider','shippingTime','processingTime'])
  vi.stubGlobal('localStorage', { getItem: () => null, clear: () => {}, setItem: () => { throw new Error('Storage blocked') } })
  await dragGroup('country', 'number')
  expect(groupKeys()[0]).toBe('country')
  expect(document.querySelector('.sheet-layout-preference [role="alert"]')?.textContent).toContain('列顺序未能保存')
  expect(exposed.capturePrices()).toEqual(prices)
})
it('includes optional WhatsApp in the preview, invalidates changed contacts and retains them on product reset', async () => {
  const state = mount()
  await click('预览报价单')
  expect(render.mock.lastCall![0].whatsapp).toBe('')
  await click('编辑报价单'); await input('WhatsApp 联系方式', '  +86 XXX XXXX XXXX  ')
  await click('预览报价单')
  expect(render.mock.lastCall![0].whatsapp).toBe('+86 XXX XXXX XXXX')
  expect(document.querySelector('.sheet-accessible p')?.textContent).toContain('WhatsApp: +86 XXX XXXX XXXX')
  await click('编辑报价单'); await input('WhatsApp 联系方式', '+1 XXX XXX XXXX')
  expect(button('复制报价图片').disabled).toBe(true)
  await click('预览报价单'); expect(render.mock.lastCall![0].whatsapp).toBe('+1 XXX XXX XXXX')
  state.contextKey = 'new-product'; await settle()
  expect(document.querySelector<HTMLInputElement>('[aria-label="WhatsApp 联系方式"]')?.value).toBe('+1 XXX XXX XXXX')
  await click('预览报价单'); expect(document.querySelector('.sheet-accessible p')?.textContent).toContain('WhatsApp: +1 XXX XXX XXXX')
})
it('blocks both kinds of sorting during a clipboard write', async () => {
  mount()
  let finish!: () => void
  writeText.mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve }))
  await click('复制报价数据')
  const keys = groupKeys()
  await dragGroup('prices', 'number')
  document.querySelector('[data-group-handle="sku"]')!.dispatchEvent(new KeyboardEvent('keydown', { key:'ArrowLeft', bubbles:true }))
  document.querySelector('.sheet-drag')!.dispatchEvent(new KeyboardEvent('keydown', { key:'ArrowRight', bubbles:true }))
  await settle()
  expect(groupKeys()).toEqual(keys)
  expect(document.querySelector<HTMLInputElement>('.sheet-quantity input')!.value).toBe('1')
  finish(); await settle()
})

async function generateWeighted() {
  await click('渠道平均报价'); await click('加权平均')
  for (const [carrier, weight] of [['SDH', '50'], ['顺丰', '30'], ['燕文', '20']]) {
    const checkbox = document.querySelector<HTMLInputElement>(`input[aria-label="参与平均：${carrier} · 内部渠道"]`)!
    checkbox.click(); await settle()
    await input(`${carrier} · 内部渠道 权重`, weight!)
  }
  await input('综合报价运输时效', '7-12 workingdays')
  await click('生成平均行')
}
const averageRows = () => ['SDH', '顺丰', '燕文'].map((carrier, i) => ({ ...row(String(i), carrier), quote1: [5,5.25,5.3][i]!, quote2: [5.95,6.25,6.45][i]!, quote3: [7,7,7.45][i]!, quoteCustom: [7,7,7.45][i]! }))
it('moves an average among source rows by drag, buttons and keyboard without changing prices, then exports that order', async () => {
  const rows = averageRows(); mount(rows); await settle(); await generateWeighted()
  const id = exposed.capturePrices().averagePlans![0]!.id
  const key = `average:${id}`
  document.querySelector<HTMLInputElement>('[aria-label="显示尺码规则列"]')!.click(); await settle()
  const rules = document.querySelector<HTMLTextAreaElement>('[aria-label="尺码规则说明"]')!
  rules.value = 'S / M / L'; rules.dispatchEvent(new Event('input', { bubbles: true })); await settle()
  await input(`综合报价 ${id} 数量 1`, '4.80')
  const original = exposed.capturePrices().averagePlans
  const keys = () => [...document.querySelectorAll<HTMLElement>('[data-row-handle]')].map(el => el.dataset.rowHandle)
  const action = (label: string) => document.querySelector<HTMLButtonElement>(`[aria-label="${label}"]`)!
  action('第 4 行排序，上下键移动').dispatchEvent(new Event('dragstart', { bubbles: true }))
  const first = document.querySelector('.sheet-editor tbody tr')!
  first.dispatchEvent(new Event('dragover', { bubbles: true, cancelable: true })); await settle()
  expect(first.classList.contains('sheet-row-drop-target')).toBe(true)
  first.dispatchEvent(new Event('drop', { bubbles: true, cancelable: true })); await settle()
  expect(keys()).toEqual([key, ...rows.map(quoteSheetRowKey)])
  expect(action('上移第 1 行').disabled).toBe(true)
  action('下移第 1 行').click(); await settle()
  expect(keys()).toEqual([quoteSheetRowKey(rows[0]!), key, ...rows.slice(1).map(quoteSheetRowKey)])
  action('第 2 行排序，上下键移动').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true })); await settle()
  expect(document.activeElement?.getAttribute('data-row-handle')).toBe(key)
  // A normal route can also be dropped onto an average row.
  action('第 4 行排序，上下键移动').dispatchEvent(new Event('dragstart', { bubbles: true }))
  document.querySelector('.sheet-average-row')!.dispatchEvent(new Event('drop', { bubbles: true, cancelable: true })); await settle()
  const expected = [quoteSheetRowKey(rows[2]!), key, ...rows.slice(0, 2).map(quoteSheetRowKey)]
  expect(keys()).toEqual(expected)
  expect(document.querySelector('.sheet-size-rules')!.getAttribute('rowspan')).toBe('4')
  expect(document.querySelector<HTMLTextAreaElement>('[aria-label="尺码规则说明"]')!.value).toBe('S / M / L')
  expect(exposed.capturePrices().averagePlans).toEqual(original)
  await exposed.copyData(); await settle()
  const copied = await write.mock.lastCall![0][0].items['text/plain'].text()
  expect(copied.indexOf('5.30')).toBeLessThan(copied.indexOf('4.80'))
  expect(copied.indexOf('4.80')).toBeLessThan(copied.indexOf('5.00'))
  await click('预览报价单')
  expect(render.mock.lastCall![0].rows.map(r => r.key)).toEqual(expected)
  expect(render.mock.lastCall![0].sizeRules).toBe('S / M / L')
})

it('keeps a moved average in place through source refresh, hiding and plan edits; deleting it removes the plan only', async () => {
  const state = mount(averageRows()); await settle(); await generateWeighted()
  const id = exposed.capturePrices().averagePlans![0]!.id
  document.querySelector<HTMLButtonElement>('[aria-label="上移第 4 行"]')!.click(); await settle()
  state.rows = state.rows.map(r => ({ ...r })); await settle()
  expect(document.querySelectorAll('[data-row-handle]')[2]!.getAttribute('data-row-handle')).toBe(`average:${id}`)
  await input('综合报价方案名称', 'Updated Shipping'); await click('应用修改')
  document.querySelector<HTMLButtonElement>('[aria-label="隐藏第 1 行"]')!.click(); await settle()
  expect(document.querySelectorAll('[data-row-handle]')[1]!.getAttribute('data-row-handle')).toBe(`average:${id}`)
  const saved = exposed.capturePrices()
  state.removalDisabled = true; await settle()
  const remove = document.querySelector<HTMLButtonElement>('[aria-label="删除第 2 行综合方案"]')!
  expect(remove.disabled).toBe(true)
  state.removalDisabled = false; await settle(); remove.click(); await settle()
  expect(document.querySelector('.sheet-average-row')).toBeNull()
  expect(document.querySelector('.average-saved')).toBeNull()
  expect(exposed.capturePrices().averagePlans).toEqual([])
  expect(exposed.capturePrices().rows).toEqual(saved.rows)
  expect(exposed.capturePrices().hiddenRowKeys).toEqual(saved.hiddenRowKeys)
  expect(button('生成平均行')).toBeTruthy()
})

it('sorts multiple averages independently and deletes just the selected plan', async () => {
  mount(averageRows()); await settle(); await generateWeighted()
  const first = exposed.capturePrices().averagePlans![0]!
  await click('新增方案'); await click('普通平均')
  for (const checkbox of document.querySelectorAll<HTMLInputElement>('.average-channel input[type="checkbox"]')) { checkbox.click(); await settle() }
  await settle(); await input('综合报价方案名称', 'Second Shipping'); await click('生成平均行')
  const second = exposed.capturePrices().averagePlans![1]!
  expect(second).toBeDefined()
  const handle = document.querySelector(`[data-row-handle="average:${first.id}"]`)!
  handle.dispatchEvent(new Event('dragstart', { bubbles: true }))
  document.querySelector('.sheet-editor tbody tr')!.dispatchEvent(new Event('drop', { bubbles: true, cancelable: true })); await settle()
  expect(document.querySelector('[data-row-handle]')!.getAttribute('data-row-handle')).toBe(`average:${first.id}`)
  document.querySelector<HTMLButtonElement>('[aria-label="删除第 1 行综合方案"]')!.click(); await settle()
  expect(exposed.capturePrices().averagePlans).toEqual([second])
  expect(document.querySelectorAll('.sheet-average-row')).toHaveLength(1)
  expect(document.querySelector('.sheet-average-row')!.textContent).toContain('AVG')
})

it('hides all average sources while retaining the editable plan, copied AVG and saved source prices', async () => {
  const rows = averageRows(); mount(rows); await settle(); await generateWeighted()
  const original = exposed.capturePrices()
  await input('综合报价 ' + original.averagePlans![0]!.id + ' 数量 1', '4.80')
  for (let i = 0; i < rows.length; i++) {
    expect(document.querySelector<HTMLButtonElement>('[aria-label="隐藏第 1 行"]')!.disabled).toBe(false)
    await hideRowAt(1)
  }
  expect(document.querySelector('.sheet-row-tools')!.textContent).toContain('显示 1 行 · 已隐藏 3 行')
  expect(document.querySelectorAll('.sheet-editor tbody tr')).toHaveLength(1)
  expect(document.querySelector('.average-saved')!.textContent).not.toContain('来源渠道已移除')
  await click('应用修改')
  const saved = exposed.capturePrices()
  expect(saved.rows).toEqual(original.rows)
  expect(saved.hiddenRowKeys).toEqual(rows.map(quoteSheetRowKey))
  expect(saved.averagePlans![0]).toMatchObject({ members: original.averagePlans![0]!.members, prices: [4.8,6.14,7.09,7.09] })
  await exposed.copyData(); await settle()
  expect(writeText.mock.lastCall![0]).toContain('AVG')
  await click('预览报价单')
  expect(render.mock.lastCall![0].rows).toHaveLength(1)
  expect(render.mock.lastCall![0].rows[0]).toMatchObject({ number: 'AVG', country: 'United States', prices: [4.8,6.14,7.09,7.09] })
  const ids = (key: string) => rows.find(row => quoteSheetRowKey(row) === key)?.channelKey
  const initialQuote = { quantities: saved.quantities, hiddenOptionIds: saved.hiddenRowKeys!.map(key => ids(key)!), rows: saved.rows.map(r => ({ optionId: ids(r.key)!, prices: r.prices })), averagePlans: mapAveragePlans(saved.averagePlans, ids) }
  const system = { quantities: saved.quantities, rows: saved.rows.map(r => ({ optionId: ids(r.key)!, prices: r.systemPrices })) }
  app.unmount(); document.body.innerHTML = ''
  const state = mount(rows, undefined, initialQuote, true); state.initialSystemQuote = system; await settle()
  await click('预览报价单')
  expect(render.mock.lastCall![0].rows).toHaveLength(1)
  expect(render.mock.lastCall![0].rows[0]!.prices[0]).toBe(4.8)
  await click('编辑报价单'); await click('恢复全部')
  expect(exposed.capturePrices().hiddenRowKeys).toEqual([])
  expect(exposed.capturePrices().rows).toEqual(saved.rows)
  expect(exposed.capturePrices().averagePlans).toEqual(saved.averagePlans)
})

it('generates weighted AVG, preserves system inputs after channel edits, and blocks invalid aggregate edits in save and copy', async () => {
  const state = mount(averageRows()); await settle()
  await input('第 1 行第 1 列美元价格', '4.80')
  await generateWeighted()
  const captured = exposed.capturePrices()
  expect(captured.averagePlans?.[0]).toMatchObject({ mode: 'weighted', systemPrices: [5.14,6.14,7.09,7.09], prices: [5.14,6.14,7.09,7.09] })
  expect(captured.rows).toHaveLength(3); expect(captured.rows[0]!.prices[0]).toBe(4.8)
  const id = captured.averagePlans![0]!.id
  await input('综合报价 ' + id + ' 数量 1', '4.80')
  expect(exposed.capturePrices().averagePlans?.[0]?.prices[0]).toBe(4.8)
  await input('综合报价 ' + id + ' 数量 1', 'bad')
  expect(() => exposed.capturePrices()).toThrow('综合报价')
  await exposed.copyData(); expect(write).not.toHaveBeenCalled()
  await input('综合报价 ' + id + ' 数量 1', '5.14')
  await click('预览报价单')
  expect(render.mock.lastCall![0].rows[3]).toMatchObject({ number: 'AVG', prices: [5.14,6.14,7.09,7.09] })
  state.rows[0]!.quote1 = 6; await settle()
  expect(exposed.capturePrices().averagePlans).toEqual([])
})
it('restores aggregate snapshots by stable channel id and keeps the saved custom quantity baseline', async () => {
  const rows = averageRows(); mount(rows); await settle(); await generateWeighted()
  const saved = exposed.capturePrices()
  const ids = (key: string) => rows.find(row => quoteSheetRowKey(row) === key)?.channelKey
  const initialQuote = { quantities: saved.quantities, rows: saved.rows.map(r => ({ optionId: ids(r.key)!, prices: r.prices })), averagePlans: mapAveragePlans(saved.averagePlans, ids) }
  const system = { quantities: saved.quantities, rows: saved.rows.map(r => ({ optionId: ids(r.key)!, prices: r.systemPrices })) }
  app.unmount(); document.body.innerHTML = ''
  const calculate = vi.fn(() => 999)
  const state = mount(rows, calculate, initialQuote, true); state.initialSystemQuote = system; await settle()
  expect(exposed.capturePrices().averagePlans?.[0]?.systemPrices).toEqual([5.14,6.14,7.09,7.09])
  expect(calculate).not.toHaveBeenCalled()
  await click('预览报价单')
  expect(render.mock.lastCall![0].rows[3]).toMatchObject({ number: 'AVG', prices: [5.14,6.14,7.09,7.09] })
})

it('updates an existing plan instead of duplicating it and outputs only AVG in summary mode', async () => {
  mount(averageRows()); await settle(); await generateWeighted()
  const id = exposed.capturePrices().averagePlans![0]!.id
  await input('综合报价 ' + id + ' 数量 1', '4.80')
  document.querySelector<HTMLInputElement>('input[type="radio"][value="summary"]')!.click(); await settle()
  await click('应用修改')
  expect(exposed.capturePrices().averagePlans![0]!.prices[0]).toBe(4.8)
  const handle = document.querySelector('[aria-label="第 1 个价格列排序，左右键移动"]')!
  handle.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true })); await settle()
  expect(exposed.capturePrices().averagePlans![0]).toMatchObject({ id, quantities: [2,1,3,5], prices: [6.14,4.8,7.09,7.09] })
  document.querySelector('[aria-label="第 2 个价格列排序，左右键移动"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true })); await settle()
  await input('SDH · 内部渠道 权重', '30'); await input('顺丰 · 内部渠道 权重', '50')
  await click('应用修改')
  expect(exposed.capturePrices().averagePlans).toHaveLength(1)
  expect(exposed.capturePrices().averagePlans![0]).toMatchObject({ id, display: 'summary', systemPrices: [5.19,6.2,7.09,7.09] })
  await click('预览报价单'); expect(render.mock.lastCall![0].rows).toHaveLength(1)
  expect(render.mock.lastCall![0].rows[0]).toMatchObject({ number: 'AVG', prices: [5.19,6.2,7.09,7.09] })
  await click('编辑报价单')
  document.querySelector<HTMLButtonElement>('[aria-label="移除综合方案 Combined Shipping"]')!.click(); await settle()
  expect(exposed.capturePrices().averagePlans).toEqual([])
  expect(button('生成平均行')).toBeTruthy()
})

it('lets users select arbitrary Australia zones of the same channel and outputs their average in the quote sheet', async () => {
  const rows = [1, 2, 3, 4].map(zone => ({ ...row('same-channel'), country: '澳大利亚', quoteRegion: `澳大利亚${zone}区`, quote1: zone * 10, quote2: zone * 20, quote3: zone * 30, quoteCustom: zone * 50 }))
  const before = JSON.stringify(rows)
  mount(rows); await settle(); await click('渠道平均报价')
  expect(document.querySelector('[aria-label="平均报价国家及区域"]')?.textContent).toContain('1–4区（自由组合）')
  expect(document.querySelectorAll('.average-channel')).toHaveLength(0)
  for (const zone of [1, 4]) {
    document.querySelector<HTMLInputElement>(`input[aria-label="参与平均区域：${zone}区"]`)!.click(); await settle()
  }
  await click('生成平均行')
  const saved = exposed.capturePrices().averagePlans![0]!
  expect(saved.systemPrices).toEqual([25, 50, 75, 125])
  expect(saved.members.map(m => m.optionId)).toEqual([quoteSheetRowKey(rows[0]!), quoteSheetRowKey(rows[3]!)])
  expect(document.querySelector('.average-saved')?.textContent).toContain('澳大利亚1区')
  expect(document.querySelector('.average-saved')?.textContent).toContain('澳大利亚4区')
  await click('预览报价单')
  expect(render.mock.lastCall![0].rows.find(r => r.number === 'AVG')).toMatchObject({ country: 'Australia', prices: [25, 50, 75, 125] })
  expect(JSON.stringify(rows)).toBe(before)
})

it('keeps region selection independent of channels and excludes removed regions from generated plans', async () => {
  mount([{ ...row('a'), country: '澳大利亚', quoteRegion: '澳大利亚1区' }, { ...row('b', '燕文'), country: '澳大利亚', quoteRegion: '澳大利亚1区' }, { ...row('c'), country: '澳大利亚', quoteRegion: '澳大利亚4区' }]); await settle(); await click('渠道平均报价')
  const zone = (n: number) => document.querySelector<HTMLInputElement>(`input[aria-label="参与平均区域：${n}区"]`)!
  const channels = () => [...document.querySelectorAll<HTMLInputElement>('.average-channel input[type="checkbox"]')]
  expect(zone(2).disabled).toBe(true); expect(zone(3).disabled).toBe(true)
  expect(channels()).toHaveLength(0)
  zone(1).click(); await settle()
  expect(channels().map(c => c.checked)).toEqual([true, true])
  for (const channel of channels()) { channel.click(); await settle() }
  expect(zone(1).checked).toBe(true); expect(zone(4).checked).toBe(false)
  expect(button('生成平均行').disabled).toBe(true)
  channels()[0]!.click(); await settle()
  expect(zone(1).checked).toBe(true); expect(zone(4).checked).toBe(false)
  zone(4).click(); await settle()
  await click('生成平均行')
  expect(exposed.capturePrices().averagePlans![0]!.members).toHaveLength(2)
  await click('新增方案')
  expect(channels()).toHaveLength(0); expect(zone(1).checked).toBe(false)
  document.querySelector<HTMLButtonElement>('button[aria-label="编辑综合方案 Combined Shipping"]')!.click(); await settle()
  expect(zone(1).checked).toBe(true); expect(zone(4).checked).toBe(true)
  expect(channels().map(c => c.checked)).toEqual([true, false, true])
  zone(1).click(); await settle()
  expect(channels().map(c => c.checked)).toEqual([true])
  expect(zone(1).checked).toBe(false); expect(zone(4).checked).toBe(true)
  expect(button('应用修改').disabled).toBe(true)
})


it('moves routes by buttons, keyboard and drag while keeping prices, times, exports and capture in sync', async () => {
  const rows = [row('first'), row('second'), row('third')]
  mount(rows)
  await input('第 2 行第 1 列美元价格', '99.50')
  await input('第 2 行运输时效', '20-25 days')
  const action = (label: string) => document.querySelector<HTMLButtonElement>('[aria-label="' + label + '"]')!
  action('上移第 2 行').click(); await settle()
  expect(exposed.capturePrices().rows.map(r => r.key)).toEqual([rows[1], rows[0], rows[2]].map(quoteSheetRowKey))
  expect(exposed.capturePrices().rows[0]!.prices[0]).toBe(99.5)
  expect(document.querySelector<HTMLInputElement>('[aria-label="第 1 行运输时效"]')!.value).toBe('20-25 days')
  expect(action('上移第 1 行').disabled).toBe(true)
  expect(action('下移第 3 行').disabled).toBe(true)
  action('第 1 行排序，上下键移动').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true })); await settle()
  expect(exposed.capturePrices().rows.map(r => r.key)).toEqual(rows.map(quoteSheetRowKey))
  action('第 3 行排序，上下键移动').dispatchEvent(new Event('dragstart', { bubbles: true })); await settle()
  const target = document.querySelectorAll('.sheet-editor tbody tr')[0]!
  target.dispatchEvent(new Event('dragover', { bubbles: true, cancelable: true })); await settle()
  expect(target.classList.contains('sheet-row-drop-target')).toBe(true)
  target.dispatchEvent(new Event('drop', { bubbles: true, cancelable: true })); await settle()
  expect(document.querySelector('.sheet-row-drop-target')).toBeNull()
  const expected = [rows[2], rows[0], rows[1]].map(quoteSheetRowKey)
  expect(exposed.capturePrices().rows.map(r => r.key)).toEqual(expected)
  await exposed.copyData(); await settle()
  expect(writeText.mock.lastCall![0].indexOf('99.50')).toBeGreaterThan(writeText.mock.lastCall![0].indexOf('10.80'))
  await click('预览报价单')
  expect(render.mock.lastCall![0].rows.map(r => r.key)).toEqual(expected)
  expect(render.mock.lastCall![0].rows[2]!.prices[0]).toBe(99.5)
})

it('preserves hidden routes and edited prices through sorting and deletion, appends new routes, and resets for another product', async () => {
  const rows = [row('first'), row('second'), row('third')]
  const state = mount(rows)
  state.resetKey = 'sku-1'; await settle()
  await input('第 3 行第 1 列美元价格', '88.50')
  document.querySelector<HTMLButtonElement>('[aria-label="隐藏第 2 行"]')!.click(); await settle()
  document.querySelector<HTMLButtonElement>('[aria-label="上移第 2 行"]')!.click(); await settle()
  expect(exposed.capturePrices().rows.map(r => r.key)).toEqual([rows[2], rows[0], rows[1]].map(quoteSheetRowKey))
  state.rows = [rows[1]!, rows[2]!]; await settle()
  expect(exposed.capturePrices().rows[0]!.prices[0]).toBe(88.5)
  state.rows.push(row('new')); await settle()
  expect(exposed.capturePrices().rows.map(r => r.key)).toEqual([rows[2], rows[1], row('new')].map(quoteSheetRowKey))
  state.resetKey = 'sku-2'; await settle()
  expect(exposed.capturePrices().rows.map(r => r.key)).toEqual(state.rows.map(quoteSheetRowKey))
  expect(exposed.capturePrices().hiddenRowKeys).toEqual([])
})

it('blocks row mutations during saving or calculation and never offers deletion in a historical record', async () => {
  const state = mount([row('first'), row('second')])
  state.canRemoveRows = true
  state.removalDisabled = true; await settle()
  expect(document.querySelector<HTMLButtonElement>('[aria-label="下移第 1 行"]')!.disabled).toBe(true)
  expect(document.querySelector<HTMLButtonElement>('[aria-label="移除第 1 行渠道"]')!.disabled).toBe(true)
  state.removalDisabled = false; state.sourcePending = true; await settle()
  expect(document.querySelector<HTMLButtonElement>('[data-row-handle]')!.getAttribute('draggable')).toBe('false')
  state.sourcePending = false; state.recordMode = true; await settle()
  expect(document.querySelector('[aria-label="移除第 1 行渠道"]')).toBeNull()
})


it('toggles the merged Size Rules cell from SKU and keeps text and prices through hide, reorder and restore', async () => {
  const state = mount([row('a'), row('b'), row('c')])
  const checkbox = () => document.querySelector<HTMLInputElement>('input[aria-label="显示尺码规则列"]')!
  const toggle = async (checked: boolean) => { checkbox().checked = checked; checkbox().dispatchEvent(new Event('change', { bubbles: true })); await settle() }
  expect(checkbox().closest('th')?.dataset.group).toBe('sku')
  expect(checkbox().checked).toBe(false)
  expect(document.querySelector('.sheet-size-rules')).toBeNull()
  const original = exposed.capturePrices().rows
  await toggle(true)
  const field = document.querySelector<HTMLTextAreaElement>('textarea[aria-label="尺码规则说明"]')!
  expect(field.maxLength).toBe(2000)
  field.value = 'S–XXL\nManual measurement: ±1–2 cm.'; field.dispatchEvent(new Event('input', { bubbles: true })); await settle()
  expect(document.querySelector('.sheet-size-rules')?.getAttribute('rowspan')).toBe('3')
  const headers = [...document.querySelectorAll('th[data-group]')].map(th => (th as HTMLElement).dataset.group)
  expect(headers.indexOf('sizeRules')).toBe(headers.indexOf('sku') + 1)
  await toggle(false)
  expect(document.querySelector('.sheet-size-rules')).toBeNull()
  expect(exposed.capturePrices()).toMatchObject({ sizeRules: field.value, sizeRulesEnabled: false })
  await toggle(true)
  expect(document.querySelector<HTMLTextAreaElement>('.sheet-size-rules textarea')!.value).toBe(field.value)
  state.rows.reverse(); await settle()
  await click('隐藏')
  expect(document.querySelector('.sheet-size-rules')?.getAttribute('rowspan')).toBe('2')
  await click('预览报价单')
  expect(render.mock.calls.at(-1)![0].sizeRules).toBe(field.value)
  expect(render.mock.calls.at(-1)![0].rows).toHaveLength(2)
  expect(exposed.capturePrices().rows.slice().sort((a,b)=>a.key.localeCompare(b.key))).toEqual(original.slice().sort((a,b)=>a.key.localeCompare(b.key)))
})

it('restores saved notes and switch state, but starts a new product with the switch off', async () => {
  const state = mount([row()], undefined, { quantities:[1], rows:[{optionId:'one',prices:[10.8]}], sizeRules:'S / M / L', sizeRulesEnabled:false }, true)
  expect(document.querySelector('.sheet-size-rules')).toBeNull()
  const checkbox = document.querySelector<HTMLInputElement>('input[aria-label="显示尺码规则列"]')!
  checkbox.checked=true;checkbox.dispatchEvent(new Event('change',{bubbles:true}));await settle()
  expect(document.querySelector<HTMLTextAreaElement>('.sheet-size-rules textarea')!.value).toBe('S / M / L')
  state.initialQuote=undefined;state.recordMode=false;state.contextKey='new-product';await settle()
  expect(document.querySelector('.sheet-size-rules')).toBeNull()
  expect(exposed.capturePrices().sizeRules).toBeUndefined()
})

it('persists average row position through capture, reopen, preview, copying and record reset', async () => {
  const rows = averageRows()
  mount(rows); await settle(); await generateWeighted()
  for (const index of [4, 3, 2]) {
    document.querySelector<HTMLButtonElement>('[aria-label="上移第 ' + index + ' 行"]')!.click()
    await settle()
  }
  const position = () => [...document.querySelectorAll('.sheet-editor tbody tr')].findIndex(tr => tr.classList.contains('sheet-average-row'))
  const movedPosition = position()
  expect(movedPosition).toBe(0)
  const saved = exposed.capturePrices()
  const ids = (key: string) => rows.find(row => quoteSheetRowKey(row) === key)?.channelKey
  const initialQuote = { rowOrder: captureQuoteRowOrder(saved.rowOrderKeys, ids), quantities: saved.quantities, rows: saved.rows.map(r => ({ optionId: ids(r.key)!, prices: r.prices })), averagePlans: mapAveragePlans(saved.averagePlans, ids) }
  const system = { quantities: saved.quantities, rows: saved.rows.map(r => ({ optionId: ids(r.key)!, prices: r.systemPrices })) }
  app.unmount(); document.body.innerHTML = ''
  const state = mount(rows, undefined, initialQuote, true); state.initialSystemQuote = system; await settle()
  const reopenedPosition = position()
  expect(exposed.capturePrices().averagePlans).toEqual(saved.averagePlans)
  expect(exposed.capturePrices().rows).toEqual(saved.rows)

  expect(reopenedPosition).toBe(movedPosition)
  await click('预览报价单')
  expect(render.mock.lastCall![0].rows[0]!.key).toBe(saved.rowOrderKeys![0])
  await click('复制报价数据')
  expect(writeText.mock.lastCall![0].indexOf('Combined Shipping')).toBeLessThan(writeText.mock.lastCall![0].indexOf('SDH'))
  state.initialQuote = undefined; state.recordMode = false; state.contextKey = 'another-record'; await settle()
  expect(exposed.capturePrices().rowOrderKeys).not.toContain(saved.rowOrderKeys![0])
})
