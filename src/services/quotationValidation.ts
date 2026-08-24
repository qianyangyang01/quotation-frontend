export type QuotationConditionIssue = { key: string; message: string }

export interface QuotationConditionInput {
  customerName: string
  quoteMode: string
  sku: string
  productCategory: string
  logisticsAttribute: string
  allowedLogisticsAttributes: readonly string[]
  customerGrade: string
  enabledCustomerGrades: readonly string[]
  taxCustomerType: string
  monthlySalesEstimate: string
}

export function validateQuotationConditions(input: QuotationConditionInput, options: { includeSku: boolean; includeCategory: boolean }) {
  const issues: QuotationConditionIssue[] = []
  if (!input.customerName.trim()) issues.push({ key: 'customerName', message: '请填写客户名称' })
  if (!['single', 'bundle'].includes(input.quoteMode)) issues.push({ key: 'quoteMode', message: '请选择报价模式' })
  if (options.includeSku && !input.sku.trim()) issues.push({ key: 'sku', message: '请输入SKU' })
  if (options.includeCategory && !input.productCategory) issues.push({ key: 'productCategory', message: '请选择产品品类' })
  if (!input.logisticsAttribute || !input.allowedLogisticsAttributes.includes(input.logisticsAttribute)) issues.push({ key: 'logisticsAttribute', message: '请选择物流属性' })
  if (!input.enabledCustomerGrades.includes(input.customerGrade)) issues.push({ key: 'customerGrade', message: '请选择已启用的客户等级' })
  if (!['A', 'B'].includes(input.taxCustomerType)) issues.push({ key: 'taxCustomerType', message: '请选择税费客户类型' })
  if (!['10', '100', '100+'].includes(input.monthlySalesEstimate)) issues.push({ key: 'monthlySalesEstimate', message: '请选择预估月销量' })
  return issues
}
