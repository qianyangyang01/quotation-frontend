// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import View from './QuotationOverviewView.vue'
const mocks=vi.hoisted(()=>({load:vi.fn()}))
vi.mock('@/services/quotationAnalyticsRecords',()=>({loadAnalyticsRecords:mocks.load}))
vi.mock('@/services/quotationAnalyticsPurchases',()=>({loadAnalyticsPurchases:vi.fn().mockResolvedValue([])}))
let app:App,root:HTMLDivElement
afterEach(()=>{app?.unmount();root?.remove();vi.clearAllMocks()})
it('shows a recoverable error instead of zero or partial statistics and disables export',async()=>{
  mocks.load.mockRejectedValueOnce(new Error('分页读取失败')).mockResolvedValueOnce([])
  root=document.createElement('div');document.body.append(root);app=createApp(View);app.component('RouterLink',{template:'<span><slot /></span>'});app.mount(root)
  await new Promise(resolve=>setTimeout(resolve,0));await nextTick()
  expect(root.querySelector('[role="alert"]')?.textContent).toContain('分页读取失败')
  expect(root.querySelector('.kpis')).toBeNull()
  expect((root.querySelector('.export')as HTMLButtonElement).disabled).toBe(true)
  ;(root.querySelector('[role="alert"] button')as HTMLButtonElement).click()
  await new Promise(resolve=>setTimeout(resolve,0));await nextTick()
  expect(root.querySelector('[role="alert"]')).toBeNull();expect(root.querySelector('.kpis')).not.toBeNull()
  expect((root.querySelector('.export')as HTMLButtonElement).disabled).toBe(false)
})
