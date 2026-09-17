// Shared by customer-sheet PNG and TSV exports, including separate record drawers.
// Web Locks also coordinates tabs on the same origin. Never queue a stale clipboard write.
let copying = false
export const QUOTE_SHEET_COPY_TIMEOUT_MS = 8000
const busy = () => new Error('其他报价单正在复制，请等待完成后重新点击复制')

// A synchronous clipboard command completes inside the originating click. It
// cannot queue behind another tab or complete later with an outdated table.
export function withSynchronousQuoteSheetCopy(write: () => boolean) {
  if (copying) throw busy()
  copying = true
  try { return write() }
  finally { copying = false }
}

export async function withQuoteSheetCopyLock(write: () => Promise<void>) {
  if (copying) throw busy()
  copying = true
  let expired = false
  let started = false
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => {
      expired = true
      reject(new Error('复制超时，浏览器未完成剪贴板操作。请刷新页面后重试；报价草稿不会因此丢失。'))
    }, QUOTE_SHEET_COPY_TIMEOUT_MS)
  })
  const perform = async () => {
    if (expired) return
    started = true
    await write()
  }
  const operation = (async () => {
    try {
      if (navigator.locks?.request) {
        await navigator.locks.request('quotation-customer-sheet-clipboard', { ifAvailable: true }, async lock => {
          if (!lock) throw busy()
          await perform()
        })
      } else await perform()
    } finally {
      // A native clipboard write cannot be cancelled. Keep its guard until it
      // settles, preventing another copy from being overwritten by a late write.
      if (!expired || started) copying = false
    }
  })()
  try {
    await Promise.race([operation, timeout])
  } finally {
    clearTimeout(timer)
    // A late lock callback is cancelled by perform(), so this case can retry.
    if (expired && !started) copying = false
  }
}
