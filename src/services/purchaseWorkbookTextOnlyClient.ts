import type { TextOnlyWorkbookProgress, TextOnlyWorkbookResult } from './purchaseWorkbookTextOnly'

type WorkerResponse = { type: 'progress'; progress: TextOnlyWorkbookProgress }
  | { type: 'result'; result: TextOnlyWorkbookResult }
  | { type: 'error'; message: string }

export function preparePurchaseWorkbookTextOnly(file: File, onProgress?: (progress: TextOnlyWorkbookProgress) => void) {
  const worker = new Worker(new URL('./purchaseWorkbookTextOnly.worker.ts', import.meta.url), { type: 'module' })
  let settled = false
  let rejectPromise: ((reason?: unknown) => void) | null = null
  const promise = new Promise<TextOnlyWorkbookResult>((resolve, reject) => {
    rejectPromise = reject
    worker.onmessage = (event: MessageEvent<WorkerResponse>) => {
      if (event.data.type === 'progress') { onProgress?.(event.data.progress); return }
      settled = true
      worker.terminate()
      if (event.data.type === 'result') resolve(event.data.result)
      else reject(new Error(event.data.message))
    }
    worker.onerror = () => {
      settled = true
      worker.terminate()
      reject(new Error('无图数据文件处理线程异常'))
    }
    worker.postMessage({ file })
  })
  return {
    promise,
    cancel: () => {
      if (settled) return
      settled = true
      worker.terminate()
      rejectPromise?.(new DOMException('处理已取消', 'AbortError'))
    },
  }
}
