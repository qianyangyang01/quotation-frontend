import {
  loadCustomerGradeSettings,
  loadFinanceChannelPolicies,
  loadFinanceCountrySettings,
  loadFinanceExchangeRate,
  type CustomerGradeSetting,
  type FinanceChannelPolicy,
  type FinanceCountrySetting,
  type FinanceExchangeRateSetting,
} from '@/data/financeChannelPolicies'
import { loadFinanceTaxSettings, type FinanceTaxSettings } from '@/data/financeTaxSettings'
import { hydrateFinanceSettings } from '@/services/financeSettings'

export type FinanceSettingsWorkspace = {
  policies: FinanceChannelPolicy[]
  countries: FinanceCountrySetting[]
  customerGrades: CustomerGradeSetting[]
  exchangeRate: FinanceExchangeRateSetting
  taxSettings: FinanceTaxSettings
}

export function readFinanceSettingsWorkspace(): FinanceSettingsWorkspace {
  return {
    policies: loadFinanceChannelPolicies(),
    countries: loadFinanceCountrySettings(),
    customerGrades: loadCustomerGradeSettings(),
    exchangeRate: loadFinanceExchangeRate(),
    taxSettings: loadFinanceTaxSettings(),
  }
}

export async function loadFinanceSettingsWorkspace(options: { force?: boolean } = {}) {
  await hydrateFinanceSettings(options)
  return readFinanceSettingsWorkspace()
}
