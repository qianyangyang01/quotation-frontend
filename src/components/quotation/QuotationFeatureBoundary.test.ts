// @vitest-environment happy-dom
import { createApp, defineAsyncComponent, defineComponent, h, nextTick, onMounted, ref, type App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import Boundary from './QuotationFeatureBoundary.vue'

let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })

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
