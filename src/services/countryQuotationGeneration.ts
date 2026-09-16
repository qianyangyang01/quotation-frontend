import { reactive, type Ref } from 'vue'
import { logisticsCountryIdentity } from '@/data/logistics'

/** Same-publication additions invalidate only the countries whose rows arrived.
 * Full reloads and publication changes still invalidate everything via global. */
export function createCountryQuotationGeneration(global: Ref<number>) {
  const generations = reactive(new Map<string, number>())
  return {
    read(country: string) {
      return `${global.value}:${generations.get(logisticsCountryIdentity(country)) || 0}`
    },
    invalidate(countries: string[]) {
      for (const key of new Set(countries.map(logisticsCountryIdentity))) {
        generations.set(key, (generations.get(key) || 0) + 1)
      }
    },
  }
}
