export const logisticsAttributeOptions = ['普货', '化妆品', '保健品', '带电', '纯电', '服装', '粉末', '非液体化妆品', '带磁', '微敏感'] as const

export function normalizeLogisticsAttribute(value: string) {
  const name = value.trim()
  return name === '纯电池' ? '纯电' : name
}

export function selectableLogisticsAttributes(values: string[]) {
  return [...new Set([...logisticsAttributeOptions, ...values.map(normalizeLogisticsAttribute)])].filter(value => value !== '液体')
}
