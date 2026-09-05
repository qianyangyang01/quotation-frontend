const PRODUCT_COUNT = 10_000

export function performanceProductNumber(sequence) {
  return ((sequence % PRODUCT_COUNT) + PRODUCT_COUNT) % PRODUCT_COUNT + 1
}

export function performanceSku(number) {
  return `PERF-SKU-${String(number).padStart(5, '0')}`
}

export function performanceBundleItem(number, quantityPerSet = 1) {
  const sku = performanceSku(number)
  return {
    sku,
    name: `性能商品 ${sku}`,
    quantityPerSet,
    effectiveWeightKg: Number(((100 + (number % 900)) / 1000).toFixed(3)),
    purchaseUnitPriceCny: Number((6 + (number % 100) / 10).toFixed(2)),
    domesticFreightPerUnitCny: 0.5,
  }
}

export function buildQuotationPayload(account, sequence, bundle, logisticsRevision = '') {
  const firstNumber = performanceProductNumber(sequence)
  const secondNumber = firstNumber === PRODUCT_COUNT ? 1 : firstNumber + 1
  const firstSku = performanceSku(firstNumber)
  const secondSku = performanceSku(secondNumber)
  const weightKg = Number((bundle
    ? performanceBundleItem(firstNumber).effectiveWeightKg + 2 * performanceBundleItem(secondNumber).effectiveWeightKg
    : performanceBundleItem(firstNumber).effectiveWeightKg).toFixed(3))
  return {
    customerName: `性能客户-${account}-${sequence}`,
    quoteMode: bundle ? 'bundle' : 'single',
    primarySku: bundle ? `${firstSku}、${secondSku}` : firstSku,
    ...(bundle ? { bundleItems: [performanceBundleItem(firstNumber, 1), performanceBundleItem(secondNumber, 2)] } : {}),
    productCategory: '服装',
    logisticsAttribute: '普货',
    customerGrade: 'A级客户',
    taxCustomerType: 'A',
    monthlySalesEstimate: '100',
    productSummary: bundle ? `${firstSku} × 1 + ${secondSku} × 2` : firstSku,
    logisticsRevision,
    quoteOptions: [{ country: '美国', carrier: '燕文', channel: '性能普货专线', quoteCustomUsd: 12.34,
      channelKey: '9001::燕文::PERF-CHANNEL', logisticsVersionId: '33333333-3333-4333-8333-333333333333',
      logisticsChannelId: '22222222-2222-4222-8222-222222222222', logisticsInput: { country: '美国', weightKg },
      freightCny: Math.round((weightKg * 48 + 8) * 100) / 100,
    }],
  }
}
