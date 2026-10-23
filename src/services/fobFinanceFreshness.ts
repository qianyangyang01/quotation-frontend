import { appUpdate } from './appUpdate'
import { financeSettingsAreHydrated, financeSettingsAreLoading, financeSettingVersions } from './financeSettings'
import { loadQuotationSync } from './quotationSync'

/** Inspect versions only: hydrating in the background would unmount the FOB
 * sheet while financePending is true, discarding its unsaved customer/layout. */
export async function checkFobFinanceVersion(signal: AbortSignal) {
  if (appUpdate.state.updateRequired || !financeSettingsAreHydrated() || financeSettingsAreLoading()) return
  const applied = financeSettingVersions()['exchange-rate']
  if (applied === undefined) return
  const snapshot = await loadQuotationSync([], signal)
  signal.throwIfAborted()
  // An explicit reload/account switch may have replaced the cache in flight.
  if (!financeSettingsAreHydrated() || financeSettingsAreLoading() || financeSettingVersions()['exchange-rate'] !== applied) return
  const current = snapshot.financeVersions?.['exchange-rate']
  if (current === undefined || !Number.isFinite(current)) throw new Error('无法确认最新财务汇率，请稍后重试核验')
  if (current !== applied) appUpdate.requireUpdate('exchange-rate')
}
