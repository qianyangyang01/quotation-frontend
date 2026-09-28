// @vitest-environment happy-dom
import { createApp, type App } from 'vue'
import { afterEach, expect, it } from 'vitest'
import QuotationWeightTrace from './QuotationWeightTrace.vue'
import { buildQuotationWeightSnapshot, type QuotationWeightItem } from '@/data/quotationWeightSnapshot'

let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })
function mount(items?: QuotationWeightItem[]) {
  const host = document.createElement('div')
  document.body.append(host)
  app = createApp(QuotationWeightTrace, { snapshot: items ? buildQuotationWeightSnapshot(items, 0, [1,2,3]) : undefined, bundle: (items?.length || 0) > 1 })
  app.mount(host)
  return host
}
it('shows a manual override and its saved procurement basis without opening details', () => {
  const host = mount([{sku:'A',quantityPerSet:1,baseWeightKg:.315,weightSource:'manual',purchaseWeightKg:.3}])
  const source = host.querySelector('.weight-sources')!
  expect(source.textContent).toContain('业务指定重量')
  expect(source.textContent).toContain('采购原重（单件）：300g → 报价采用（单件）：315g（较采购原重 +15g）')
  expect(source.closest('details')).toBeNull()
  expect(host.querySelector('tbody tr')!.textContent).toBe('1件31570322')
})
it('distinguishes procurement and manual sources per SKU including equal weights', () => {
  const host = mount([
    {sku:'A',quantityPerSet:2,baseWeightKg:.05,weightSource:'purchase',purchaseWeightKg:.05},
    {sku:'B',quantityPerSet:1,baseWeightKg:.1,weightSource:'manual',purchaseWeightKg:.1},
    {sku:'C',quantityPerSet:1,baseWeightKg:.0397,weightSource:'manual',purchaseWeightKg:.05},
  ])
  const sources = host.querySelectorAll('.weight-source')
  expect(sources[0]!.textContent).toContain('采购表重量A × 2')
  expect(sources[1]!.textContent).toContain('业务指定重量B × 1')
  expect(sources[1]!.textContent).toContain('与采购原重相同')
  expect(sources[2]!.textContent).toContain('较采购原重 -10.3g')
})
it('does not guess missing provenance on historical snapshots', () => {
  const host = mount([{sku:'OLD',quantityPerSet:1,baseWeightKg:.315}])
  expect(host.querySelector('.weight-sources')!.textContent).toContain('来源未记录')
  expect(host.textContent).toContain('无法判断是否由业务修改')
  expect(host.querySelector('.manual')).toBeNull()
})
it('keeps records without a packaging snapshot explicitly unknown', () => {
  const host = mount()
  expect(host.textContent).toContain('无法判断是否由业务修改')
  expect(host.textContent).toContain('不按当前规则回算')
  expect(host.querySelector('table')).toBeNull()
})
