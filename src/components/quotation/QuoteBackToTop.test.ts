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
it('moves by pointer without treating a drag as a return-to-top click, then allows a normal click', async () => {
  vi.spyOn(window, 'scrollY', 'get').mockReturnValue(900)
  const scroll=vi.spyOn(window,'scrollTo').mockImplementation(()=>{})
  mount(document.createElement('div'));await nextTick()
  const button=document.querySelector('button')!
  vi.spyOn(button,'getBoundingClientRect').mockReturnValue({left:800,top:600,width:76,height:64} as DOMRect)
  button.setPointerCapture=vi.fn()
  button.dispatchEvent(new PointerEvent('pointerdown',{pointerId:1,isPrimary:true,button:0,clientX:810,clientY:610}))
  button.dispatchEvent(new PointerEvent('pointermove',{pointerId:1,clientX:510,clientY:410,cancelable:true}))
  button.dispatchEvent(new PointerEvent('pointerup',{pointerId:1}))
  button.dispatchEvent(new MouseEvent('click',{detail:1}));await nextTick()
  expect(button.style.left).toBe('500px');expect(button.style.top).toBe('400px')
  expect(scroll).not.toHaveBeenCalled()
  button.dispatchEvent(new PointerEvent('pointerdown',{pointerId:2,isPrimary:true,button:0,clientX:510,clientY:410}))
  button.dispatchEvent(new PointerEvent('pointerup',{pointerId:2}))
  button.dispatchEvent(new MouseEvent('click',{detail:1}));await nextTick()
  expect(scroll).toHaveBeenCalledTimes(1)
})
it('clamps dragging and resize to the viewport and cancels cleanly', async () => {
  vi.spyOn(window,'scrollY','get').mockReturnValue(900)
  mount(document.createElement('div'));await nextTick()
  const button=document.querySelector('button')!
  vi.spyOn(button,'getBoundingClientRect').mockReturnValue({left:800,top:600,width:76,height:64} as DOMRect)
  button.setPointerCapture=vi.fn()
  button.dispatchEvent(new PointerEvent('pointerdown',{pointerId:1,isPrimary:true,button:0,clientX:810,clientY:610}))
  button.dispatchEvent(new PointerEvent('pointermove',{pointerId:1,clientX:-500,clientY:-500,cancelable:true}))
  await nextTick();expect(button.style.left).toBe('8px');expect(button.style.top).toBe('8px')
  button.dispatchEvent(new PointerEvent('pointermove',{pointerId:1,clientX:5000,clientY:5000,cancelable:true}))
  button.dispatchEvent(new PointerEvent('pointercancel',{pointerId:1}));await nextTick()
  expect(button.classList.contains('dragging')).toBe(false)
  vi.spyOn(window,'innerWidth','get').mockReturnValue(400)
  vi.spyOn(window,'innerHeight','get').mockReturnValue(300)
  window.dispatchEvent(new Event('resize'));await nextTick()
  expect(button.style.left).toBe('316px');expect(button.style.top).toBe('228px')
})
