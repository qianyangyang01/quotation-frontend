// @vitest-environment happy-dom
import { createApp, h, nextTick, ref } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import type { App } from 'vue'
import type { FinanceTaxSettings } from '@/data/financeTaxSettings'
import { logisticsCountries } from '@/data/logistics'
import { matchesFinanceCountry } from '@/data/financeCountrySearch'

vi.mock('@/data/financeChannelPolicies', () => ({ channelsAvailableForCountry: () => [] }))
import Component from './ChannelTaxSettings.vue'

let app: App | undefined
const originalCountries = [...logisticsCountries]
afterEach(() => {
  app?.unmount(); document.body.innerHTML = ''
  logisticsCountries.splice(0, logisticsCountries.length, ...originalCountries)
})
function mount(saving = false) {
  const value = ref<FinanceTaxSettings>({ countries: [
    { country: '欧盟', selected: true, enabled: true, sortOrder: 1, fixedFeeUsd: 3.51 },
    { country: '美国', selected: true, enabled: true, sortOrder: 2, fixedFeeUsd: 0.3 },
    { country: '罗马尼亚', selected: false, enabled: false, sortOrder: 3, fixedFeeUsd: 0 },
    { country: 'RO', selected: false, enabled: false, sortOrder: 4, fixedFeeUsd: 0 },
    ...Array.from({ length: 24 }, (_, i) => ({ country: `测试国家${i}`, selected: false, enabled: false, sortOrder: i + 5, fixedFeeUsd: 0 })),
  ], providers: [], updatedAt: '' })
  const update = vi.fn((next: FinanceTaxSettings) => { value.value = next })
  const save = vi.fn()
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(Component, { modelValue: value.value, 'onUpdate:modelValue': update, onSave: save, exchange: { usdCny: 6.7 }, saving }) })
  app.mount(host)
  return { value, update, save, host }
}
async function search(text: string) {
  const input = document.querySelector<HTMLInputElement>('[aria-label="搜索税费国家"]')!
  input.value = text; input.dispatchEvent(new Event('input')); await nextTick()
}

it.each(['罗马尼亚', '罗马', 'ro', ' RO ', 'Romania', 'romani'])('finds an unselected country by %s without changing settings', async term => {
  const { update, save, host } = mount()
  await search(term)
  expect(host.querySelector('[aria-label="添加罗马尼亚税费设置"]')).not.toBeNull()
  expect(host.querySelectorAll('.country-results li')).toHaveLength(1)
  expect(host.textContent).toContain('当前沿用欧盟')
  expect(update).not.toHaveBeenCalled(); expect(save).not.toHaveBeenCalled()
})

it('adds directly, selects the country, clears search and preserves all fees without publishing', async () => {
  const { value, update, save, host } = mount()
  const before = JSON.parse(JSON.stringify(value.value)) as FinanceTaxSettings
  await search('ro')
  host.querySelector<HTMLButtonElement>('[aria-label="添加罗马尼亚税费设置"]')!.click(); await nextTick()
  expect(update).toHaveBeenCalledTimes(1); expect(save).not.toHaveBeenCalled()
  const expected = structuredClone(before); expected.countries[2]!.selected = true
  expect(value.value).toEqual(expected)
  expect(host.querySelector('.matrix h3')?.textContent).toBe('罗马尼亚')
  expect(host.querySelector<HTMLInputElement>('[aria-label="搜索税费国家"]')!.value).toBe('')
  expect(host.textContent).toContain('发布前请核对该国全部渠道')
  await search('Romania')
  expect(host.querySelector('[aria-label="已添加税费国家"]')?.textContent).toContain('罗马尼亚')
  expect(host.querySelector('[aria-label="添加RO税费设置"]')).toBeNull()
  expect(host.querySelector('.country-results li')).toBeNull()
})

it('limits browse results to ten, supports more and resets the limit on search', async () => {
  const { host } = mount()
  expect(host.querySelector('[aria-label="添加税费国家"]')).toBeNull()
  host.querySelector<HTMLButtonElement>('.browse-countries')!.click(); await nextTick()
  expect(host.querySelectorAll('.country-results li')).toHaveLength(10)
  host.querySelector<HTMLButtonElement>('.more-countries')!.click(); await nextTick()
  expect(host.querySelectorAll('.country-results li')).toHaveLength(20)
  await search('测试')
  expect(host.querySelectorAll('.country-results li')).toHaveLength(10)
  await search('不存在的国家')
  expect(host.textContent).toContain('未找到匹配国家')
  host.querySelector<HTMLButtonElement>('[aria-label="清空国家搜索"]')!.click(); await nextTick()
  expect(host.querySelectorAll('.country-results li')).toHaveLength(10)
})

it('blocks adding while saving', async () => {
  const { host, update } = mount(true)
  await search('RO')
  const add = host.querySelector<HTMLButtonElement>('[aria-label="添加罗马尼亚税费设置"]')!
  expect(add.disabled).toBe(true); add.click(); await nextTick()
  expect(update).not.toHaveBeenCalled()
})

it('searches non-EU catalogue countries by Chinese, code and English, and supports EU', () => {
  logisticsCountries.push({ code: 'JP', name: '日本' }, { code: 'US', name: '美国' })
  for (const term of ['日本', 'JP', 'japan']) expect(matchesFinanceCountry('日本', term)).toBe(true)
  for (const term of ['US', 'United States', '美国']) expect(matchesFinanceCountry('美国', term)).toBe(true)
  for (const term of ['欧盟', 'EU', 'European Union', '27']) expect(matchesFinanceCountry('欧盟', term)).toBe(true)
  expect(matchesFinanceCountry('克罗地亚', 'RO')).toBe(false)
  expect(matchesFinanceCountry('欧盟', 'ro')).toBe(false)
})
