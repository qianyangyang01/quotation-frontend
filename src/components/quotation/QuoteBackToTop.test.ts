// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import QuoteBackToTop from './QuoteBackToTop.vue'

let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.restoreAllMocks() })
function mount(host: HTMLElement) { document.body.append(host); app = createApp({ render: () => h(QuoteBackToTop) }); app.mount(host) }
it('shows only after scrolling and returns the page to the top with reduced-motion support', async () => {
  vi.spyOn(window, 'scrollY', 'get').mockReturnValue(0)
  const scroll = vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
  vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: true } as MediaQueryList)
  mount(document.createElement('div')); await nextTick()
  expect(document.querySelector('button')).toBeNull()
  vi.spyOn(window, 'scrollY', 'get').mockReturnValue(900)
  window.dispatchEvent(new Event('scroll')); await nextTick()
  document.querySelector('button')!.click()
  expect(scroll).toHaveBeenCalledWith({ top: 0, behavior: 'instant' })
})
it('scrolls the record dialog rather than the page behind it', async () => {
  const dialog = document.createElement('dialog'); dialog.open = true; dialog.style.overflowY = 'auto'
  Object.defineProperty(dialog, 'scrollHeight', { value: 2000 }); Object.defineProperty(dialog, 'clientHeight', { value: 700 })
  dialog.scrollTop = 800
  const scroll = vi.spyOn(dialog, 'scrollTo').mockImplementation(() => {})
  const pageScroll = vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
  mount(dialog); await nextTick(); document.querySelector('button')!.click()
  expect(scroll).toHaveBeenCalled(); expect(pageScroll).not.toHaveBeenCalled()
  dialog.open = false; window.dispatchEvent(new Event('scroll')); await nextTick()
  expect(document.querySelector('button')).toBeNull()
})
