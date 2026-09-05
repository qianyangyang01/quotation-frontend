import { describe, expect, it, vi } from 'vitest'
import { reactive, ref } from 'vue'
import { createCountryQuotationCache } from './countryQuotationCache'

describe('country quotation dependency cache', () => {
  it('reuses a country and isolates Australian zone changes while tracking all price inputs', () => {
    const zones = reactive({ AU: '1区', US: '' })
    const inputs = reactive({ cost: 10, freight: 2, weight: 1, quantity: 1, rate: 7, tax: 0, grade: 1, tier: 1, invoice: 1, bundle: 1 })
    const generation = ref(0)
    const calculate = vi.fn((country: string) => ({
      country, zone: zones[country as keyof typeof zones], generation: generation.value,
      price: Object.values(inputs).reduce((sum, value) => sum + value, 0),
    }))
    const get = createCountryQuotationCache(calculate)
    const us = get('US')
    get('AU')
    expect(get('US')).toBe(us)
    zones.AU = '2区'
    expect(get('US')).toBe(us)
    expect(get('AU').zone).toBe('2区')
    expect(calculate.mock.calls.map(([country]) => country)).toEqual(['US', 'AU', 'AU'])
    for (const key of Object.keys(inputs) as Array<keyof typeof inputs>) {
      const before = get('US')
      inputs[key]++
      expect(get('US')).not.toBe(before)
    }
    generation.value++
    expect(get('US').generation).toBe(1)
    expect(get('AU').generation).toBe(1)
  })
})
