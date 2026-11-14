import { computed } from 'vue'
import { currentAuthUser } from '@/data/authStore'
export const fobClassifications = [
  { value: 'rebate-fob', label: '可报关退税-FOB/EXW', english: 'FOB / EXW', special: true },
  { value: 'rebate-cfr-cif', label: '可报关退税CFR（C&F）、CIF', english: 'CFR / CIF', special: true },
  { value: 'ordinary-fob', label: '普通报价-FOB', english: 'FOB', special: false },
] as const
export type FobClassification = typeof fobClassifications[number]['value']
export const canViewFobCoefficient = computed(() => currentAuthUser.value.role === 'super_admin')
export const fobClassificationLabel = (value?: string) => fobClassifications.find(c => c.value === value)?.label || 'FOB'
export const fobClassificationEnglish = (value?: string) => fobClassifications.find(c => c.value === value)?.english || 'FOB'

export function isFobSpecialInvoice(value:string) {
 const text=value.normalize('NFKC').replace(/[\s\u200b]+/g,'')
 return ['专票','含专票','增值税专用发票','special'].includes(text)||/^(?:专票|增值税专用发票)(?:1|3|6|9|10|13)(?:%|个点)?$/.test(text)
}
