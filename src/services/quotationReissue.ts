import type { QuotationRecord, QuotationRecordQuoteOption } from '@/data/quotationRecords'
import { draftSelection, type QuotationDraftPayload } from './quotationDrafts'

/** Copy editable inputs only. Prices, approvals and deal results belong to the original record. */
export function quotationReissuePayload(record: QuotationRecord): QuotationDraftPayload {
  const options: Array<Pick<QuotationRecordQuoteOption, 'country' | 'carrier' | 'channel' | 'rule'> & Partial<QuotationRecordQuoteOption>> = record.quoteOptions?.length ? record.quoteOptions : record.specifiedQuotes?.length
    ? record.specifiedQuotes : [{ country: record.country, carrier: record.carrier, channel: record.channel, rule: record.rule }]
  const primary = record.quoteOptions?.find(option => option.isPrimary) || options[0]
  const selections = draftSelection(options.map(option => ({
    country: option.country, quoteRegion: 'quoteRegion' in option ? option.quoteRegion : undefined,
    channelKey: 'channelKey' in option ? option.channelKey : undefined,
    rule: option.rule, carrier: option.carrier, transport: option.channel,
  })))
  const mode = record.matrixMode || 'common'
  const selectedQuoteRegions = Object.fromEntries(selections.filter(item => item.quoteRegion).map(item => [item.country, item.quoteRegion!]))
  if (primary?.country) selectedQuoteRegions[primary.country] = primary.quoteRegion || ''
  return {
    schemaVersion: 2, customerName: record.customerName, selectedCustomerId: record.customerOperation?.id,
    commissionThreshold: record.commissionThreshold ?? 1,
    specialPackagingGrams: record.weightSnapshot?.specialPackagingGrams ?? 0,
    quoteMode: record.quoteMode, skuSearch: record.quoteMode === 'single' ? record.primarySku : '',
    productCategory: record.productCategory || '', logisticsAttribute: record.logisticsAttribute,
    selectedCustomerGrade: record.customerGrade === '新客户' ? 'NEW' : record.customerGrade.replace(/级客户$/, ''),
    monthlySalesEstimate: record.monthlySalesEstimate || '10', customQuoteQuantity: record.customQuoteQuantity || 5,
    quoteMatrixMode: mode,
    selectedQuoteRegions,
    product: {
      sku: record.quoteMode === 'single' ? record.primarySku : '', purchaseInvoiceTaxApplied: record.purchaseInvoiceTaxApplied,
      quantity: 1, weightSource: 'purchase', manualWeight: 0,
      volumetricEnabled: false, packageLengthCm: record.packageLengthCm || 0,
      packageWidthCm: record.packageWidthCm || 0, packageHeightCm: record.packageHeightCm || 0,
      volumeDivisor: record.defaultVolumeDivisor || 8000,
      primaryCountry: primary?.country || '', primaryChannelKey: primary && 'channelKey' in primary ? primary.channelKey || '' : '',
      primaryRule: primary?.rule || '', primaryCarrier: primary?.carrier || '',
    },
    bundleItems: (record.bundleItems || []).map(item => ({ sku: item.sku, quantityPerSet: item.quantityPerSet,
      customWeightKg: null, purchaseInvoiceTaxApplied: item.purchaseInvoiceTaxApplied })),
    commonSelections: mode === 'common' ? selections : [], specifiedSelections: mode === 'specified' ? selections : [],
    templateSelections: mode === 'template' ? selections : [],
    activeTemplate: mode === 'template' && record.quotationTemplateId
      ? { id: record.quotationTemplateId, name: record.quotationTemplateName || '个人报价模板' } : null,
  }
}
