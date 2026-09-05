import { ref } from 'vue'

export function createLogisticsPublishFeedback() {
  const phase = ref<'idle' | 'publishing' | 'refreshing' | 'done' | 'unconfirmed'>('idle')
  const detail = ref('')
  const total = ref(0), completed = ref(0), elapsed = ref(0)
  let timer: ReturnType<typeof setInterval> | undefined, pollTimer: ReturnType<typeof setTimeout> | undefined, epoch = 0
  function stop() { epoch++; clearInterval(timer); clearTimeout(pollTimer) }
  async function execute<T>(submit: () => Promise<T>, accept: (result: T) => void, refresh: () => Promise<void>, progress?: { total: number; read: () => Promise<number> }) {
    if (phase.value === 'publishing' || phase.value === 'refreshing') return
    stop()
    const runEpoch = epoch, started = Date.now()
    total.value = progress?.total || 1; completed.value = 0; elapsed.value = 0
    timer = setInterval(() => { elapsed.value = Math.floor((Date.now() - started) / 1000) }, 1000)
    const poll = async () => {
      if (epoch !== runEpoch || phase.value !== 'publishing' || !progress) return
      try {
        const count = await progress.read()
        if (epoch === runEpoch && phase.value === 'publishing') completed.value = Math.max(completed.value, Math.min(total.value, count))
      } catch { /* A progress read failure must not interrupt the publication request. */ }
      if (epoch === runEpoch && phase.value === 'publishing') pollTimer = setTimeout(() => { void poll() }, 1500)
    }
    phase.value = 'publishing'
    if (progress) pollTimer = setTimeout(() => { void poll() }, 500)
    detail.value = '正在提交发布，请勿重复点击或关闭页面。'
    let result: T
    try { result = await submit() } catch (error) {
      stop()
      phase.value = 'unconfirmed'
      detail.value = `暂未确认发布结果：${error instanceof Error ? error.message : '请求中断'}。请先查看渠道状态再重试，部分渠道可能已经发布。`
      return
    }
    phase.value = 'refreshing'
    clearTimeout(pollTimer)
    completed.value = total.value
    detail.value = '已收到发布结果，正在更新渠道列表。'
    try {
      accept(result)
      await refresh()
      detail.value = '发布结果和渠道列表已更新。'
    } catch {
      detail.value = '发布结果已确认，但列表更新失败。请刷新页面查看，无需重复发布。'
    } finally { stop(); phase.value = 'done' }
  }
  function reset() { stop(); phase.value = 'idle'; detail.value = ''; total.value = 0; completed.value = 0; elapsed.value = 0 }
  return { phase, detail, total, completed, elapsed, execute, reset, stop }
}
