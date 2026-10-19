// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { clearPricingTestPermissions, setPricingTestPermissions } from '@/test/pricingPermissions'
import type { DomesticFreightWarning as Warning } from '@/domain/domesticFreightWarning'
import DomesticFreightWarning from './DomesticFreightWarning.vue'

const warning: Warning = {
  regionLabel: '浙江省 / 金华市', quantity: 100, weightG: 145, totalWeightG: 14500, chargeableKg: 15, rate: 1.5,
  actualTotal: 87.654321, expectedTotal: 26, excessTotal: 61.654321,
  actualUnit: 0.87654321, expectedUnit: 0.26, excessUnit: 0.61654321,
}
let app: App | undefined
let root: HTMLDivElement
beforeEach(() => { root = document.createElement('div'); document.body.append(root) })
afterEach(() => { app?.unmount(); app = undefined; root.remove(); clearPricingTestPermissions() })
async function mount(data = warning) {
  app = createApp(DomesticFreightWarning, { warning: data, sku: 'NOTICE-260001' })
  app.mount(root)
  await nextTick()
}

describe('domestic freight warning presentation', () => {
  it('keeps the requested notice visible without leaking procurement amounts or location to quote-only users', async () => {
    setPricingTestPermissions(['quote'])
    await mount()
    expect(root.textContent).toContain('运费超出合理范围，请联系采购确认是否有误！')
    expect(root.textContent).toContain('本提示仅供核对，可继续正常报价。')
    expect(root.textContent).toContain('NOTICE-260001')
    expect(root.innerHTML).not.toContain('87.654321')
    expect(root.innerHTML).not.toContain('61.654321')
    expect(root.innerHTML).not.toContain('金华市')
    expect(root.innerHTML).not.toContain('14500')
    expect(root.querySelector('.warning-values')).toBeNull()
    expect(root.querySelector('.warning-calculation')).toBeNull()
  })

  it('labels the selected batch total and hides sensitive detail immediately if permission is removed', async () => {
    setPricingTestPermissions(['quote', 'purchase'])
    await mount()
    expect(root.textContent).toContain('当前 100 件总运费')
    expect(root.textContent).toContain('87.654321')
    expect(root.textContent).toContain('145g × 100件 = 14,500g')
    expect(root.textContent).toContain('折合 ¥0.26/件')
    setPricingTestPermissions(['quote'])
    await nextTick()
    expect(root.innerHTML).not.toContain('87.654321')
    expect(root.querySelector('.warning-values')).toBeNull()
  })

  it('provides native expandable calculation rules as an inline status without a dialog or confirmation action', async () => {
    setPricingTestPermissions(['quote'])
    await mount()
    expect(root.querySelector('aside')?.getAttribute('role')).toBe('status')
    const rules = root.querySelector('details')!
    expect(rules).not.toBeNull()
    expect(rules.firstElementChild?.tagName).toBe('SUMMARY')
    expect(rules.firstElementChild?.textContent).toContain('查看计算规则')
    expect(rules.textContent).toContain('按当前采购阶梯实际采用的运费档位')
    expect(rules.textContent).toContain('实际运费严格大于参考金额的 1.5 倍时预警，正好超出 50% 不预警')
    expect(root.querySelector('.warning-advisory')?.textContent).toContain('实际运费超过参考金额 50% 时预警')
    expect(root.querySelector('dialog, [role="dialog"], [role="alertdialog"], button')).toBeNull()
  })

  it('preserves sub-cent precision in the excess over the reference amount', async () => {
    setPricingTestPermissions(['quote', 'purchase'])
    await mount({ ...warning, quantity: 10, actualTotal: 9.751, expectedTotal: 6.5, excessTotal: 3.251,
      actualUnit: 0.9751, expectedUnit: 0.65, excessUnit: 0.3251, weightG: 100.01, totalWeightG: 1000.1, chargeableKg: 2 })
    expect(root.querySelector('.warning-excess')?.textContent).toBe('¥3.251')
  })
})
