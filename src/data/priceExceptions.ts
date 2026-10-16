export const priceExceptionOptions = [
  { value: 'purchase-price', label: '采购价格异常', hint: '请采购核实并更新价格、票点或采购运费' },
  { value: 'channel-price', label: '渠道价格异常', hint: '请核实渠道价格及计费规则' },
  { value: 'entry-error', label: '业务填写错误', hint: '请核实数量、规格或报价参数' },
  { value: 'other', label: '其他 / 原因待核实', hint: '请核实异常原因后再归类' },
] as const
export type PriceExceptionType = typeof priceExceptionOptions[number]['value']
export interface PriceException {
  category: PriceExceptionType
  recordedAt: string
  targets: Array<{ sku: string; source: string; dataSource?: string; procurementOwner: string; attribution: 'assigned' | 'unassigned' }>
}
export const priceExceptionLabel = (value?: string) => priceExceptionOptions.find(option => option.value === value)?.label || '历史未分类'

export function priceExceptionOpinion(category?: string, note = ''): string {
  const option = priceExceptionOptions.find(item => item.value === category)
  const text = note.trim()
  if (!option) return text
  const prefix = `${option.label}，${option.hint}`
  return text.startsWith(prefix) ? text : [prefix, text].filter(Boolean).join('；')
}

// Only replace the generated opening; preserve the reviewer's supplementary text.
export function changeExceptionOpinion(note: string, previous?: string, next?: string): string {
  const prefix = priceExceptionOpinion(previous)
  const text = prefix && note.trim().startsWith(prefix) ? note.trim().slice(prefix.length).replace(/^[；\s]+/, '') : note
  return priceExceptionOpinion(next, text)
}
