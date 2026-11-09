export const logisticsAttributeOptions = ['普货', '化妆品', '保健品', '带电', '纯电', '服装', '香水', '大货普货', '大货带电', '以色列自提', '以色列到门', '粉末', '非液体化妆品', '带磁', '微敏感'] as const

export function normalizeLogisticsAttribute(value: string) {
  const name = value.trim()
  return name === '纯电池' ? '纯电' : name
}

export function selectableLogisticsAttributes(values: string[]) {
  return [...new Set([...logisticsAttributeOptions, ...values.map(normalizeLogisticsAttribute)])].filter(value => value !== '液体')
}

// Quotation picker grouping only; finance policies and historical attributes remain distinct.
export const israelDeliveryAttributes = ['以色列自提', '以色列到门'] as const
export type IsraelDeliveryMode = '自提' | '到门'
export function quotationLogisticsAttribute(value: string) {
  const attribute = normalizeLogisticsAttribute(value)
  return (israelDeliveryAttributes as readonly string[]).includes(attribute) ? '以色列' : attribute
}
export function quotationLogisticsAttributeOptions(values: string[]) {
  const authorized = new Set(values.map(normalizeLogisticsAttribute))
  return [...new Set(selectableLogisticsAttributes(values).filter(value => authorized.has(value)).map(quotationLogisticsAttribute))]
}
