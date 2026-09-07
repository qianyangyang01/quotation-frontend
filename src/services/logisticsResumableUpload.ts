import { api, ApiError, uploadForm, type UploadProgress } from './http'
import { currentAuthUser } from '@/data/authStore'
import type { Batch } from '@/data/logisticsRebuild'

type UploadState = { id: string; chunkBytes: number; received: string[][]; batch?: Batch }
type SavedUpload = { key: string; expires: number }
const PREFIX = 'quotation:logistics-upload:v1:'
export async function sha256(blob: Blob, signal?: AbortSignal) {
  if (typeof Worker !== 'undefined') {
    return new Promise<string>((resolve, reject) => {
      const worker = new Worker(new URL('./logisticsHash.worker.ts', import.meta.url), { type: 'module' })
      const abort = () => { worker.terminate(); reject(new DOMException('已暂停上传', 'AbortError')) }
      if (signal?.aborted) { abort(); return }
      signal?.addEventListener('abort', abort, { once: true })
      worker.onmessage = (event: MessageEvent<{ hash?: string; error?: string }>) => {
        signal?.removeEventListener('abort', abort)
        worker.terminate()
        if (event.data.hash) resolve(event.data.hash)
        else reject(new Error(event.data.error || '文件校验失败'))
      }
      worker.onerror = () => { signal?.removeEventListener('abort', abort); worker.terminate(); reject(new Error('文件校验线程启动失败，请刷新后重试')) }
      worker.postMessage(blob)
    })
  }
  const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())
  return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('')
}

export function resumableLogisticsUpload(dataset: string, input: File[], replaceDrafts: boolean, key: string, progress?: (value: UploadProgress) => void) {
  const files: File[] = []
  const controller = new AbortController()
  const cancellations = new Set<() => void>()
  const checkCancelled = () => { if (controller.signal.aborted) throw new DOMException('已暂停上传', 'AbortError') }
  const promise = (async () => {
    progress?.({ loaded: 0, total: input.reduce((sum, file) => sum + file.size, 0), percent: 0, bytesPerSecond: 0, phase: 'hashing' })
    const entries = []
    for (const file of input) {
      checkCancelled()
      entries.push({ file, spec: { name: file.name, size: file.size, sha256: await sha256(file, controller.signal) } })
    }
    entries.sort((a, b) => a.spec.name.localeCompare(b.spec.name) || a.spec.sha256.localeCompare(b.spec.sha256))
    files.push(...entries.map(entry => entry.file))
    const specs = entries.map(entry => entry.spec)
    checkCancelled()
    const manifest = { files: specs, replaceDrafts }
    const signature = await sha256(new Blob([JSON.stringify([currentAuthUser.value.account, dataset, manifest])]))
    const storageKey = PREFIX + signature
    const now = Date.now()
    // Persist only identity, never workbook data. Server ownership and hashes remain authoritative.
    try {
      const raw = localStorage.getItem(storageKey)
      const saved = raw ? JSON.parse(raw) as SavedUpload : null
      if (saved && saved.expires > now) key = saved.key
      localStorage.setItem(storageKey, JSON.stringify({ key, expires: now + 7 * 86400_000 }))
    } catch { throw new Error('浏览器无法保存续传记录，请允许本站本地存储后重试') }
    let state: UploadState
    try { state = await api.post<UploadState>(`/logistics/rebuild/datasets/${dataset}/uploads`, manifest, key) }
    catch (error) {
      if (!(error instanceof ApiError) || error.code !== 'UPLOAD_EXPIRED') throw error
      checkCancelled()
      key = crypto.randomUUID()
      localStorage.setItem(storageKey, JSON.stringify({ key, expires: now + 7 * 86400_000 }))
      state = await api.post<UploadState>(`/logistics/rebuild/datasets/${dataset}/uploads`, manifest, key)
    }
    checkCancelled()
    if (state.batch) { localStorage.removeItem(storageKey); return state.batch }
    if (state.chunkBytes !== 4 * 1024 * 1024 || state.received.length !== files.length) throw new Error('服务器续传信息无效')
    const total = files.reduce((sum, file) => sum + file.size, 0)
    let loaded = files.reduce((sum, file, index) => sum + Math.min(file.size, state.received[index]!.length * state.chunkBytes), 0)
    const resumedBytes = loaded, started = performance.now()
    const inFlightByFile = new Map<number, number>()
    const report = () => {
      const inFlight = [...inFlightByFile.values()].reduce((sum, bytes) => sum + bytes, 0)
      progress?.({ phase: 'uploading',
      loaded: Math.min(total, loaded + inFlight), total,
      percent: Math.min(100, Math.floor((loaded + inFlight) * 100 / total)),
      bytesPerSecond: Math.max(0, loaded + inFlight - resumedBytes) / Math.max(0.001, (performance.now() - started) / 1000),
      })
    }
    report()
    let nextFile = 0
    const sendFiles = async () => {
    while (nextFile < files.length) {
      const index = nextFile++
      const file = files[index]!
      for (let chunk = state.received[index]!.length; chunk * state.chunkBytes < file.size; chunk++) {
        checkCancelled()
        const blob = file.slice(chunk * state.chunkBytes, (chunk + 1) * state.chunkBytes)
        const hash = await sha256(blob, controller.signal)
        for (let attempt = 0; ; attempt++) {
          checkCancelled()
          const form = new FormData(); form.append('chunk', blob, 'chunk'); form.append('sha256', hash)
          const task = uploadForm<UploadState>(`/logistics/rebuild/uploads/${state.id}/files/${index}/chunks/${chunk}`, form,
            value => { inFlightByFile.set(index, value.total ? Math.floor(blob.size * value.loaded / value.total) : 0); report() })
          cancellations.add(task.cancel)
          try { await task.promise; break }
          catch (error) {
            checkCancelled()
            if (!(error instanceof ApiError) || (error.status !== 0 && error.status < 500) || attempt >= 2)
              throw error instanceof ApiError && error.status === 0 ? new Error('网络中断，已保存上传进度；重新选择同一批原文件即可续传（保留7天）') : error
          } finally { cancellations.delete(task.cancel); inFlightByFile.delete(index) }
        }
        loaded += blob.size; report()
      }
    }
    }
    try { await Promise.all([sendFiles(), sendFiles()]) }
    catch (error) { controller.abort(); cancellations.forEach(cancel => cancel()); throw error }
    checkCancelled()
    progress?.({ loaded: total, total, percent: 100, bytesPerSecond: 0, phase: 'confirming' })
    const batch = await api.post<Batch>(`/logistics/rebuild/uploads/${state.id}/complete`)
    localStorage.removeItem(storageKey)
    return batch
  })()
  return { promise, cancel: () => { controller.abort(); cancellations.forEach(cancel => cancel()) } }
}
