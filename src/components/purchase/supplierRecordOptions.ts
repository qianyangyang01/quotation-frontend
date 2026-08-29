export const QUALITY_OPTIONS = [
  { value: '优', label: '优：没有次品和短少' },
  { value: '良', label: '良：有次品但可退换' },
  { value: '不良', label: '不良：多次异常，不可退换单' },
] as const

export function normalizeQualityGrade(value: string) {
  const normalized = value.trim()
  if (/^(?:A|A（优）|A\(优\)|优)$/i.test(normalized)) return '优'
  if (/^(?:B|B（良）|B\(良\)|良)$/i.test(normalized)) return '良'
  if (/^(?:C|C（不良）|C\(不良\)|不良)$/i.test(normalized)) return '不良'
  return normalized
}

export function qualityLabel(value: string | null | undefined) {
  const normalized = normalizeQualityGrade(value || '')
  return QUALITY_OPTIONS.find((option) => option.value === normalized)?.label || normalized || '暂无数据'
}

export function validDeliveryDays(value: string) {
  return value === '' || /^[1-9]\d*$/.test(value)
}

export function deliveryLabel(value: string | null | undefined) {
  const normalized = value?.trim() || ''
  if (!normalized) return '暂无数据'
  return /^\d+$/.test(normalized) ? `${normalized} 天` : normalized
}
