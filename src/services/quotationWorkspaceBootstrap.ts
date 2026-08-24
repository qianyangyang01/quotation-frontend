import { loadFinanceChannelPolicies, loadFinanceCountrySettings } from '@/data/financeChannelPolicies'
import { loadFinanceTaxSettings } from '@/data/financeTaxSettings'
import { loadPublishedLogisticsManifest } from '@/data/publishedLogisticsRepository'
import { hydrateFinanceSettings } from '@/services/financeSettings'

export async function loadQuotationWorkspaceConfiguration() {
  await Promise.all([
    hydrateFinanceSettings(),
    loadPublishedLogisticsManifest(),
  ])

  return {
    countrySettings: loadFinanceCountrySettings(),
    taxSettings: loadFinanceTaxSettings(),
    channelPolicies: loadFinanceChannelPolicies(),
  }
}
