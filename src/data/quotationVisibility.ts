import { computed } from 'vue'
import { hasPermission } from './authStore'

// Use the session's effective module permissions, including custom role grants.
// These controls govern presentation; API authorization is a separate boundary.
// Domestic freight is visible in quotation workspaces and saved record details.
export const canViewDomesticFreight = computed(() =>
  hasPermission('quote') || hasPermission('myRecords') || hasPermission('allRecords') || hasPermission('purchase'))
export const canViewPurchaseCost = computed(() => hasPermission('purchase'))
export const canViewLogisticsCost = computed(() => hasPermission('logistics'))
export const canViewPricingFactors = computed(() => hasPermission('finance'))
export const canViewFullPricing = computed(() => canViewPurchaseCost.value && canViewLogisticsCost.value && canViewPricingFactors.value)
