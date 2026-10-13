// @vitest-environment happy-dom
import { createApp, defineAsyncComponent, defineComponent, h, nextTick, onMounted, ref, type App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import Boundary from './QuotationFeatureBoundary.vue'

let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.restoreAllMocks() })

it.each([
  'Failed to fetch dynamically imported module: /assets/FobRecordDetail-old.js',
  'Unable to preload CSS for /assets/old.css',
])('offers explicit page refresh for resource failure without losing unsaved work: %s', async message => {
  const reload = vi.spyOn(window.location, 'reload').mockImplementation(() => {})
  const AsyncChild = defineAsyncComponent(() => Promise.reject(new TypeError(message)))
  app = createApp({ render: () => h('main', [
    h('input', { value: '未保存的报价内容' }),
    h(Boundary, { label: 'FOB报价详情' }, () => h(AsyncChild)),
  ]) })
  app.mount(document.body.appendChild(document.createElement('div')))
  await vi.waitFor(() => expect(document.querySelector('[role=alert]')?.textContent).toContain('刷新整个页面'))
  expect(document.querySelector('input')?.value).toBe('未保存的报价内容')
  expect(reload).not.toHaveBeenCalled()
  Array.from(document.querySelectorAll('button')).find(button => button.textContent === '刷新页面')!.click()
  expect(reload).toHaveBeenCalledTimes(1)
})

it.each(['load', 'setup', 'render', 'mounted'] as const)('contains a %s failure and preserves the surrounding quotation input', async phase => {
  const open = ref(false), broken = ref(true), customer = ref('普通报价客户')
  const failure = () => { throw new Error('batch failure') }
  const Child = defineComponent({
    setup() {
      if (broken.value && phase === 'setup') failure()
      onMounted(() => { if (broken.value && phase === 'mounted') failure() })
      return () => {
        if (broken.value && phase === 'render') failure()
        return h('p', '批量组件正常')
      }
    },
  })
  const loader = vi.fn(async () => { if (broken.value && phase === 'load') failure(); return Child })
  const AsyncChild = defineAsyncComponent(loader)
  const globalError = vi.fn()
  app = createApp({ setup: () => () => h('main', [
    h('input', { value: customer.value, onInput: (e: Event) => { customer.value = (e.target as HTMLInputElement).value } }),
    open.value ? h(Boundary, { label: '批量报价', onClose: () => { open.value = false } }, () => h(AsyncChild)) : null,
  ]) })
  app.config.errorHandler = globalError
  app.mount(document.body.appendChild(document.createElement('div')))
  expect(loader).not.toHaveBeenCalled()
  const originalInput = document.querySelector('input')!
  open.value = true
  await vi.waitFor(() => expect(document.querySelector('[role="alert"]')?.textContent).toContain('其他报价功能可继续使用'))
  expect(globalError).not.toHaveBeenCalled()
  expect(document.querySelector('input')).toBe(originalInput)
  expect(originalInput.value).toBe('普通报价客户')
  document.querySelector('button')!.click(); await nextTick()
  expect(document.querySelector('[role="alert"]')).toBeNull()
  originalInput.value = '继续报价'; originalInput.dispatchEvent(new Event('input'))
  expect(customer.value).toBe('继续报价')
  broken.value = false; open.value = true
  await vi.waitFor(() => expect(document.body.textContent).toContain('批量组件正常'))
  expect(globalError).not.toHaveBeenCalled()
})
