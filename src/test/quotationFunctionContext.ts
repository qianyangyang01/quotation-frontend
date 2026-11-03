import { isBundleQuotation, isSingleQuotation, isWomensQuotation } from '@/data/quotationModes'
/** Dependencies for legacy tests that extract live view functions instead of mounting Vue. */
export const quotationFunctionContext = {
  isBundleQuotation, isSingleQuotation, isWomensQuotation,
  womensPricingBlock: { value: '' }, womensPricing: { value: null },
}
