import { computed, type ComputedRef } from 'vue'

/**
 * Each country tracks its own actual reactive pricing inputs. Do not use a
 * hand-maintained string key: missing a cost/tax input can serve stale prices.
 * The owner must read the published-rule generation in calculate().
 */
export function createCountryQuotationCache<T>(calculate: (country: string) => T) {
  const entries = new Map<string, ComputedRef<T>>()
  return (country: string): T => {
    let entry = entries.get(country)
    if (!entry) {
      entry = computed(() => calculate(country))
      entries.set(country, entry)
    }
    return entry.value
  }
}
