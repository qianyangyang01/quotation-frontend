// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import Panel from './FobQuotePanel.vue'
import { FOB_SMALL_ORDER_POLICY } from '@/services/fobQuotation'
import { api } from '@/services/http'

const batch = vi.hoisted(() => ({ loaded: vi.fn(), mounted: vi.fn() }))
vi.mock('./FobBatchQuoteDialog.vue', () => {
  batch.loaded()
  return { __esModule: true, default: { setup() { batch.mounted(); throw new Error('批量模块运行失败') } } }
})
let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.restoreAllMocks() })

it('does not load the batch editor for a single FOB quote, and contains its failure when opened', async () => {
  const requests = vi.spyOn(api, 'get').mockRejectedValue(new Error('Unexpected request'))
  const globalError = vi.fn()
  app = createApp(Panel, { rate: 6.7, policy: FOB_SMALL_ORDER_POLICY })
  app.config.errorHandler = globalError
  app.mount(document.body.appendChild(document.createElement('div')))
  const sku = document.querySelector<HTMLInputElement>('[aria-label="FOB查询SKU"]')!
  sku.value = 'KEEP-SKU'; sku.dispatchEvent(new Event('input')); await nextTick()
  expect(batch.loaded).not.toHaveBeenCalled()
  const open = [...document.querySelectorAll('button')].find(b => b.textContent === '批量粘贴报价')!
  open.click()
  await vi.waitFor(() => expect(document.querySelector('[role="alert"]')?.textContent).toContain('FOB批量报价暂时无法使用'))
  expect(batch.mounted).toHaveBeenCalledOnce()
  expect(globalError).not.toHaveBeenCalled()
  expect(document.querySelector('[aria-label="FOB查询SKU"]')).toBe(sku)
  expect(sku.value).toBe('KEEP-SKU')
  ;[...document.querySelectorAll('button')].find(b => b.textContent === '关闭当前功能')!.click()
  await nextTick()
  expect(document.querySelector('[role="alert"]')).toBeNull()
  expect(document.querySelector<HTMLButtonElement>('.query-submit')!.disabled).toBe(false)
  expect(requests).not.toHaveBeenCalled()
})
