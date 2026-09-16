// @vitest-environment happy-dom
import { createApp, h, nextTick, reactive, ref } from 'vue'
import { expect, it } from 'vitest'
import CountrySelect from './FinanceCountrySelect.vue'
import RuleList from './FinanceCountryRuleList.vue'
import type { FinanceCountryChannelRule } from '@/data/financeChannelPolicies'

it('searches a large country list by code and name and selects with keyboard or mouse', async () => {
  const host = document.createElement('div'); document.body.append(host)
  const selected = ref('美国')
  const countries = [{ country: '美国', code: 'US' }, { country: '德国', code: 'DE' }, ...Array.from({ length: 138 }, (_, i) => ({ country: `国家${i}`, code: `X${i}` }))]
  const app = createApp({ render: () => h(CountrySelect, { modelValue: selected.value, countries, 'onUpdate:modelValue': value => { selected.value = value } }) }); app.mount(host)
  try {
    host.querySelector<HTMLButtonElement>('[aria-label="选择国家"]')!.click(); await nextTick()
    const search = host.querySelector<HTMLInputElement>('input')!
    search.value = 'de'; search.dispatchEvent(new Event('input')); await nextTick()
    expect(host.querySelectorAll('[role=option]')).toHaveLength(1)
    expect(host.textContent).toContain('德国')
    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' })); await nextTick()
    expect(selected.value).toBe('德国'); expect(host.querySelector('input')).toBeNull()
    host.querySelector<HTMLButtonElement>('[aria-label="选择国家"]')!.click(); await nextTick()
    const secondSearch = host.querySelector<HTMLInputElement>('input')!
    secondSearch.value = '美国'; secondSearch.dispatchEvent(new Event('input')); await nextTick()
    host.querySelector<HTMLButtonElement>('[role=option]')!.click(); await nextTick()
    expect(selected.value).toBe('美国')
    host.querySelector<HTMLButtonElement>('[aria-label="选择国家"]')!.click(); await nextTick()
    host.querySelector('input')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })); await nextTick()
    expect(host.querySelector('[role=listbox]')).toBeNull()
  } finally { app.unmount(); host.remove() }
})

it('keeps edits across pages and filtering, retains original indices, reveals additions and clamps after removal', async () => {
  const host = document.createElement('div'); document.body.append(host)
  const rules = reactive<FinanceCountryChannelRule[]>(Array.from({ length: 101 }, (_, index) => ({ country: `国家${index}`, allowedChannels: [], stage: 'standard', continent: '亚洲', sortOrder: index + 1 })))
  const codes = new Map(rules.map((rule,index) => [rule.country, { code: `X${index}` }]))
  const list = ref<InstanceType<typeof RuleList>>()
  const app = createApp({ render: () => h(RuleList, { ref: list, rules, codes }, { default: ({rule,index}: {rule: FinanceCountryChannelRule; index: number}) => h('label', { class: 'rule', 'data-index': index }, [rule.country, h('input', { type: 'checkbox', checked: rule.allowedChannels.includes('channel'), onChange: () => { rule.allowedChannels = rule.allowedChannels.length ? [] : ['channel'] } })]) }) }); app.mount(host)
  const advance = async () => { Array.from(host.querySelectorAll<HTMLButtonElement>('button')).find(button => button.textContent === '下一页')!.click(); await nextTick() }
  try {
    expect(host.querySelectorAll('.rule')).toHaveLength(10)
    host.querySelector<HTMLInputElement>('.rule input')!.click(); await nextTick(); await advance()
    host.querySelector<HTMLInputElement>('.rule input')!.click(); await nextTick()
    expect(rules[0]!.allowedChannels).toEqual(['channel']); expect(rules[10]!.allowedChannels).toEqual(['channel'])
    const filter = host.querySelector<HTMLInputElement>('[aria-label="筛选已配置国家"]')!
    filter.value = 'X100'; filter.dispatchEvent(new Event('input')); await nextTick()
    expect(host.querySelectorAll('.rule')).toHaveLength(1); expect(host.querySelector('.rule')!.getAttribute('data-index')).toBe('100')
    host.querySelector<HTMLInputElement>('.rule input')!.click(); await nextTick()
    expect(rules[100]!.allowedChannels).toEqual(['channel'])
    await list.value!.reveal(100); await nextTick()
    expect(filter.value).toBe(''); expect(host.textContent).toContain('第 11 / 11 页')
    rules.pop(); await nextTick(); await nextTick()
    expect(host.textContent).toContain('第 10 / 10 页')
    for (const size of [30, 50]) {
      const select = host.querySelector<HTMLSelectElement>('select')!; select.value = String(size); select.dispatchEvent(new Event('change')); await nextTick()
      expect(host.querySelectorAll('.rule')).toHaveLength(size)
      expect(host.querySelector<HTMLInputElement>('.rule input')!.checked).toBe(true)
    }
    expect(rules).toHaveLength(100); expect(rules[10]!.allowedChannels).toEqual(['channel'])
  } finally { app.unmount(); host.remove() }
})
