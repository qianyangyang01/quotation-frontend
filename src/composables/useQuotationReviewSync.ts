import { computed, onMounted, onUnmounted, ref, watch, type Ref } from 'vue'
import { loadQuotationReviewStates, type QuotationRecord, type QuotationReviewState } from '@/data/quotationRecords'

/** Keep live badges separate from editors' saved versions, so polling cannot hide an edit conflict. */
export function useQuotationReviewSync(rows: Ref<QuotationRecord[]>, selected: Ref<QuotationRecord | null>, account: Ref<string>) {
  const states = ref<Record<string, QuotationReviewState>>({})
  const error = ref('')
  const ids = computed(() => [...new Set([...rows.value.map(row => row.id), ...(selected.value ? [selected.value.id] : [])])].sort().join(','))
  let timer: ReturnType<typeof setTimeout> | undefined
  let controller: AbortController | undefined
  let generation = 0
  let disposed = false
  function newer(a:QuotationReviewState,b?:QuotationReviewState) {
    return !b || (a._version??-1)>(b._version??-1) || ((a._version??-1)===(b._version??-1)&&(a._reviewVersion??0)>=(b._reviewVersion??0))
  }
  function accept(state: QuotationReviewState) {
    if (newer(state,states.value[state.id])) states.value[state.id] = state
  }
  function stop() { generation++; clearTimeout(timer); controller?.abort() }
  async function poll() {
    stop()
    if (disposed || !account.value || !ids.value || document.visibilityState === 'hidden') return
    const current = generation
    const requestController = new AbortController()
    controller = requestController
    const timeout = setTimeout(() => requestController.abort(), 10_000)
    try {
      const result = await loadQuotationReviewStates(ids.value.split(','), requestController.signal)
      if (current !== generation) return
      for (const state of result) accept(state)
      error.value = ''
    } catch {
      if (current === generation) error.value = '审核状态同步失败，正在重试；当前显示可能不是最新状态'
    } finally {
      clearTimeout(timeout)
      if (current === generation && !disposed) timer = setTimeout(() => void poll(), 3000)
    }
  }
  watch([ids, account], () => { states.value = {}; error.value = ''; void poll() }, { immediate: true })
  const wake = () => { void poll() }
  onMounted(() => { window.addEventListener('focus', wake); document.addEventListener('visibilitychange', wake); window.addEventListener('online', wake) })
  onUnmounted(() => { disposed = true; stop(); window.removeEventListener('focus', wake); document.removeEventListener('visibilitychange', wake); window.removeEventListener('online', wake) })
  function stateFor(row: QuotationRecord): QuotationReviewState {
    const state = states.value[row.id]
    return state && newer(state,row) ? state : row
  }
  return { stateFor, accept, error, poll }
}
