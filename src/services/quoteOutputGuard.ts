import { computed } from 'vue'
import { appUpdate } from './appUpdate'

export const QUOTE_UPDATE_MESSAGE = '系统已更新，当前未保存报价已暂停预览、复制和导出。请先保留输入，刷新后重新查询并报价。'

/** Explicit caller provenance keeps saved historical snapshots exportable. */
export function useQuoteOutputGuard(currentQuote: () => boolean) {
  const outputBlocked = computed(() => currentQuote() && appUpdate.state.updateRequired)
  function assertOutputCurrent() {
    // Synchronous: clipboard writes must retain the user's activation.
    if (currentQuote()) appUpdate.assertCurrent()
  }
  return { outputBlocked, assertOutputCurrent }
}
