import { describe, expect, it, vi } from 'vitest'
import { reactive, ref } from 'vue'
import { createCountryQuotationGeneration } from './countryQuotationGeneration'
import { createCountryQuotationCache } from './countryQuotationCache'
import { replaceLogisticsCountryCatalog } from '@/data/logistics'

describe('country-scoped logistics invalidation', () => {
  it('reuses existing prices when another country arrives, including alias invalidation', () => {
    replaceLogisticsCountryCatalog([{ code: 'US', name: '美国' }, { code: 'NL', name: '荷兰' }, { code: 'AE', name: '阿联酋' }])
    const global = ref(0)
    const generation = createCountryQuotationGeneration(global)
    const inputs = reactive({ weight: 1, quantity: 5, finance: 7, cost: 20 })
    const calculate = vi.fn((country: string) => ({ country, revision: generation.read(country), ...inputs }))
    const get = createCountryQuotationCache(calculate)
    const us = get('美国'), nl = get('荷兰'), ae = get('阿联酋')
    generation.invalidate(['NL'])
    expect(get('美国')).toBe(us)
    expect(get('阿联酋')).toBe(ae)
    expect(get('荷兰')).not.toBe(nl)
    expect(calculate.mock.calls.map(([country]) => country)).toEqual(['美国', '荷兰', '阿联酋', '荷兰'])
    generation.invalidate(['阿拉伯联合酋长国', 'AE'])
    expect(get('阿联酋').revision).toBe('0:1')
    for (const key of Object.keys(inputs) as Array<keyof typeof inputs>) {
      const before = get('美国')
      inputs[key]++
      expect(get('美国')).not.toBe(before)
    }
    global.value++
    expect(get('美国').revision).toBe('1:0')
    expect(get('荷兰').revision).toBe('1:1')
  })
})
