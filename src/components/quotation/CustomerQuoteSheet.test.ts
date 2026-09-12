// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import CustomerQuoteSheet from './CustomerQuoteSheet.vue'
import { renderCustomerQuoteSheet, type QuoteSheetImage } from '@/services/customerQuoteSheetRenderer'
import type { QuoteSheetPriceCalculator, QuoteSheetSourceRow } from '@/data/customerQuoteSheet'

vi.mock('@/services/customerQuoteSheetRenderer', async importOriginal => ({
  ...await importOriginal<typeof import('@/services/customerQuoteSheetRenderer')>(),
  renderCustomerQuoteSheet: vi.fn(),
}))
let app: App
const render = vi.mocked(renderCustomerQuoteSheet)
const writeText = vi.fn(), write = vi.fn(), revoke = vi.fn()
function row(key = 'one', carrier = '花海'): QuoteSheetSourceRow {
  return { country: '美国', quoteRegion: '全国统一', carrier, channelKey: key, ruleId: 1, rule: '内部规则', channelCode: key, transport: '内部渠道', eta: '8～12 天', quote1: 10.8, quote2: 16.35, quote3: 21.9, quoteCustom: 30.85 }
}
function png(firstRow = 1, lastRow = 1): QuoteSheetImage {
  return { blob: new Blob(['png'], { type: 'image/png' }), width: 1536, height: 1024, firstRow, lastRow }
}
function mount(rows = [row()], calculatePrice?: QuoteSheetPriceCalculator) {
  const state = reactive({ rows, countries: [], salesperson: 'Alex', contextKey: 'product-1', customQuantity: 5, bundle: false, sourcePending: false, calculatePrice, resetKey: undefined as string | undefined })
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(CustomerQuoteSheet, state) }); app.mount(host)
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
  vi.clearAllMocks()
  render.mockReset().mockResolvedValue([png()])
  write.mockReset().mockResolvedValue(undefined); writeText.mockReset().mockResolvedValue(undefined)
  vi.stubGlobal('isSecureContext', true)
  vi.stubGlobal('navigator', { clipboard: { writeText, write } })
  vi.stubGlobal('ClipboardItem', class { constructor(public items: Record<string, Blob>) {} })
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:quote')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(revoke)
})
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.restoreAllMocks(); vi.unstubAllGlobals() })

it('previews the formerly blocked carriers and copies the exact preview blob and all 9 table columns', async () => {
  const state = mount([row(), row('two', '闪电猴')]); const original = JSON.stringify(state.rows)
  await click('预览报价单')
  expect(document.querySelector('img')?.src).toBe('blob:quote')
  expect(render.mock.calls[0][0].rows.map(row => row.provider)).toEqual(['Hua Hai', 'SDH Express'])
  await click('复制报价图片')
  expect(write.mock.calls[0][0][0].items['image/png']).toBe((await render.mock.results[0].value)[0].blob)
  await click('复制报价数据')
  const cells = writeText.mock.calls[0][0].split('\r\n').map((line: string) => line.split('\t'))
  expect(cells).toHaveLength(3); expect(cells.every((line: string[]) => line.length === 9)).toBe(true)
  expect(cells[2]).toEqual(['2', 'US', 'SDH Express', '8-12 days', '1-2 days', '$10.80', '$16.35', '$21.90', '$30.85'])
  expect(writeText.mock.calls[0][0]).not.toMatch(/内部|全国统一/)
  expect(JSON.stringify(state.rows)).toBe(original)
})

it('previews and copies a saved missing-ETA route, supports a local supplement and restores the unknown value', async () => {
  const state = mount([{ ...row('legacy', '极通环球'), eta: '该物流暂无时效说明' }])
  expect(document.querySelector<HTMLInputElement>('[aria-label="第 1 行运输时效"]')!.value).toBe('')
  await click('预览报价单'); expect(render.mock.lastCall![0].rows[0]!.shippingTime).toBe('—')
  await click('复制报价数据'); expect(writeText.mock.lastCall![0]).toContain('JITO\t—\t1-2 days')
  await click('编辑报价单'); await input('第 1 行运输时效', '10-15 days')
  await click('复制报价数据'); expect(writeText.mock.lastCall![0]).toContain('JITO\t10-15 days')
  await click('恢复渠道时效'); await click('复制报价数据')
  expect(writeText.mock.lastCall![0]).toContain('JITO\t—\t1-2 days')
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
  expect(writeText.mock.lastCall![0].split('\r\n')[2]).toContain('15-20 days')
  state.contextKey = 'product-2'; await settle(); await click('复制报价数据')
  expect(writeText.mock.lastCall![0]).not.toContain('15-20 days')
  state.rows[0]!.eta = '3～5 天'; state.rows[0]!.quote1 = 99.5; await settle(); await click('复制报价数据')
  expect(writeText.mock.lastCall![0]).toContain('3-5 days\t1-2 days\t$99.50')
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
  expect(snapshot.rows[0]).toMatchObject({ country: 'CA', provider: 'Custom Carrier', processingTime: '3-4 days', prices: [10.8,16.35,21.9,30.85,27.5] })
  expect(snapshot.rows[1].prices[4]).toBeNull()
  const cells = writeText.mock.lastCall![0].split('\r\n').map((line: string) => line.split('\t'))
  expect(cells.every((cells: string[]) => cells.length === 10)).toBe(true)
  expect(cells[1][9]).toBe('$27.50'); expect(cells[2][9]).toBe('—')
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
  await click('复制报价数据'); expect(writeText.mock.lastCall![0].split('\r\n')[0].split('\t')).toHaveLength(15)
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
  expect(writeText.mock.lastCall![0].split('\r\n')[1].split('\t')[5]).toBe('$77.75')
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
  expect(writeText.mock.lastCall![0].split('\r\n')[0].split('\t').slice(5)).toEqual(['2 pcs (USD)','1 pc (USD)','3 pcs (USD)','5 pcs (USD)'])
  expect(writeText.mock.lastCall![0].split('\r\n')[1].split('\t').slice(5)).toEqual(['$16.35','$66.66','$21.90','$30.85'])
})
