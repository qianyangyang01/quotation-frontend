// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import { normalizeFinanceTaxSettings } from '@/data/financeTaxSettings'

vi.mock('@/data/financeChannelPolicies', () => ({ channelsAvailableForCountry: (country: string) => {
  const name = country === 'US' ? '美国' : country === 'NZ' ? '新西兰' : country
  return Array.from({ length: 13 }, (_, index) => [
    { key: `${index + 1}::燕文::Y-${index}`, carrier: '燕文', channel: `${name}${index < 2 ? '化妆品' : '普货'}${index + 1}`, ruleName: '燕文' },
    ...(index < 2 ? [{ key: `${index + 21}::万邦::W-${index}`, carrier: '万邦', channel: `${name}化妆品${index + 1}`, ruleName: '万邦' }] : []),
  ]).flat()
},
}))
import Component from './ChannelTaxSettings.vue'

let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })
async function mount() {
  const value = ref(normalizeFinanceTaxSettings({ countries: ['美国','新西兰'].map(country => ({ country, selected: true, enabled: true, fixedFeeUsd: 0, sortOrder: 1 })) }))
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(Component, { modelValue: value.value, 'onUpdate:modelValue': next => value.value = next, exchange: { usdCny: 6.7 }, saving: false }) })
  app.mount(host); await nextTick()
  return { host, value }
}
function checkbox(host: ParentNode, label: string) { return host.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)! }
async function click(host: ParentNode, label: string) {
  const button = [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.getAttribute('aria-label') === label || item.textContent?.trim() === label)
  expect(button, label).toBeTruthy(); button!.click(); await nextTick()
}

it('groups interleaved carriers and applies a provider selection across its pages without touching other carriers', async () => {
  const { host, value } = await mount()
  const groups = [...host.querySelectorAll<HTMLElement>('.provider-group')]
  expect(groups.map(group => group.getAttribute('aria-label'))).toEqual(['燕文渠道分组', '万邦渠道分组'])
  expect(groups[0]!.querySelectorAll('tbody tr')).toHaveLength(10)
  expect(groups[1]!.querySelector('table')).toBeNull()
  checkbox(host, '全选燕文全部渠道').click(); await nextTick()
  expect(host.textContent).toContain('已选 13 个渠道')
  expect(checkbox(host, '全选万邦全部渠道').checked).toBe(false)
  await click(host, '燕文下一页')
  expect(groups[0]!.querySelectorAll('tbody tr')).toHaveLength(3)
  expect(checkbox(host, '选择燕文美国普货11').checked).toBe(true)
  checkbox(host, '选择燕文美国普货11').click(); await nextTick()
  expect(checkbox(host, '全选燕文全部渠道').indeterminate).toBe(true)
  await click(host, '批量设置'); await click(host, '应用到所选渠道')
  const rules = value.value.countries.find(row => row.country === '美国')!.channelRules!
  expect(rules).toHaveLength(12)
  expect(rules.every(rule => rule.key.includes('::燕文::') && rule.amount === .3)).toBe(true)
  expect(rules.some(rule => rule.key === '11::燕文::Y-10')).toBe(false)
  expect(value.value.countries.find(row => row.country === '新西兰')!.channelRules).toBeUndefined()
})

it('limits provider bulk selection to search matches, preserves other selections, and clears selections on country changes', async () => {
  const { host } = await mount()
  checkbox(host, '选择燕文美国普货3').click(); await nextTick()
  checkbox(host, '全选万邦全部渠道').click(); await nextTick()
  const search = host.querySelector<HTMLInputElement>('[aria-label="搜索税费渠道"]')!
  search.value = '化妆品'; search.dispatchEvent(new Event('input')); await nextTick()
  expect(host.querySelectorAll('.provider-group table')).toHaveLength(2)
  checkbox(host, '全选燕文筛选结果').click(); await nextTick()
  expect(host.textContent).toContain('已选 5 个渠道')
  checkbox(host, '全选燕文筛选结果').click(); await nextTick()
  expect(host.textContent).toContain('已选 3 个渠道')
  search.value = ''; search.dispatchEvent(new Event('input')); await nextTick()
  expect(checkbox(host, '选择燕文美国普货3').checked).toBe(true)
  expect(checkbox(host, '全选万邦全部渠道').checked).toBe(true)
  expect(checkbox(host, '全选燕文全部渠道').indeterminate).toBe(true)
  await click(host, '新西兰')
  expect(host.textContent).toContain('已选 0 个渠道')
  expect(checkbox(host, '全选燕文全部渠道').indeterminate).toBe(false)
  expect(checkbox(host, '全选万邦全部渠道').checked).toBe(false)
})
