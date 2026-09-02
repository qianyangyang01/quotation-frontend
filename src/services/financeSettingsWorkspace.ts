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
import { api } from '@/services/http'

export type FinanceRequiredPreviewDataset = {
  id: string
  name: string
  status: 'preparing'
  createdAt: string
  revision: number
  confirmed: boolean
  requiredCount: number
  confirmedBy?: string
  confirmedAt?: string
}

export type FinanceRequiredPreviewChannel = {
  id: string
  name: string
  providerName: string
  code: string
  channelKey: string
  logisticsAttribute?: string
  currentVersionId?: string | null
  quoteReady: boolean
  countries: string[]
  zones: string[]
  priceRows: number
  pendingReasons: string[]
}

export type FinanceRequiredPreview = {
  datasetId: string
  datasetName: string
  status: 'preparing'
  revision: number
  confirmed: boolean
  note?: string
  confirmedBy?: string
  confirmedAt?: string
  requiredCount: number
  readyCount: number
  channels: FinanceRequiredPreviewChannel[]
}

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

export function loadFinanceRequiredPreviewDatasets() {
  return api.get<FinanceRequiredPreviewDataset[]>('/finance-settings/logistics-required-previews')
}

export function loadFinanceRequiredPreview(datasetId: string) {
  return api.get<FinanceRequiredPreview>(`/finance-settings/logistics-required-previews/${datasetId}`)
}
