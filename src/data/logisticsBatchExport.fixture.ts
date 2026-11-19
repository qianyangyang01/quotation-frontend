import type { LogisticsPriceRow } from './logistics'
import type { RealPriceSnapshot } from './logisticsRealPriceHistory'

/** Only imported by the isolated local preview and tests. Never used by production history. */
export function batchExportFixture(): RealPriceSnapshot {
  const price = (countryCode: string, weightFromKg: number, weightToKg: number, pricePerKg: number, registrationFee: number): LogisticsPriceRow => ({
    countryCode, areaName: countryCode, weightFromKg, weightToKg, pricePerKg, registrationFee, pricingModel: 'per-kg-1g', weightFromInclusive: false, weightToInclusive: true,
    minChargeWeightKg: 0, startWeightKg: 0, firstWeightKg: 0, firstWeightPrice: 0, nextWeightKg: 0, nextWeightPrice: 0, intervalPrice: 0, surcharge: 0, fuelSurchargeRate: 0,
    zoneName: '', zoneExclude: false, quoteReady: true, etaMinDays: 5, etaMaxDays: 10, prohibitedMarks: '', allowedMarks: '', maxPerimeterCm: 0, maxSideCm: 0,
    volumeDivisor: 0, prohibitGeneralCargo: false, volumetric: false, phoneRequired: false,
  })
  const definitions = [
    { code: 'US', name: '美国专线', old: [[0, .5, 96, 12], [.5, 1, 76, 12], [1, 2, 68, 12]], next: [[0, .5, 100, 12], [.5, 1, 79, 12], [1, 2, 68, 14]] },
    { code: 'GB', name: '英国专线', old: [[0, .5, 88, 10], [.5, 1, 66, 10], [1, 2, 58, 10]], next: [[0, .5, 84, 10], [.5, 1, 62, 10], [1, 2, 58, 8]] },
    { code: 'DE', name: '德国专线', old: [[0, .5, 82, 9], [.5, 1, 64, 9], [1, 2, 55, 9]], next: [[0, .5, 82, 9], [.5, 1, 64, 9], [1, 2, 55, 9]] },
    { code: 'FR', name: '法国专线', old: [], next: [[0, 2, 60, 9]] },
    { code: 'ES', name: '西班牙专线', old: [[0, 2, 58, 10]], next: [] },
    { code: 'IT', name: '意大利专线', old: [[0, 2, 60, 20]], next: [[0, 2, 64, 18]] },
  ]
  return {
    capturedAt: '2026-11-19T02:00:00Z', dataset: 'local-demo-only',
    importBatches: [{ id: 'DEMO-20261118-01', importedAt: '2026-11-18T03:36:00Z', operator: '演示操作人' }],
    channels: definitions.map((d, i) => ({ id: d.code, ruleId: i + 1, code: d.code, name: d.name, provider: '样例物流商', attribute: '普货', current: `${d.code}-2`, enabled: true, quoteReady: true })),
    versions: definitions.flatMap(d => [d.old, d.next].map((prices, i) => ({ id: `${d.code}-${i + 1}`, channel: d.code, number: i + 1, status: i ? 'published' : 'superseded', publishedAt: i ? '2026-11-18T03:36:00Z' : '2026-11-17T03:00:00Z', fileName: '样例价表.xlsx', baseVersionId: i ? `${d.code}-1` : '', importBatchId: i ? 'DEMO-20261118-01' : null, rows: prices.map(r => price(d.code, r[0]!, r[1]!, r[2]!, r[3]!)) }))),
  }
}
