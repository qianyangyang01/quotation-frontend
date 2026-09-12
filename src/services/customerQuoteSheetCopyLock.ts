// Shared by customer-sheet PNG and TSV exports, including separate record drawers.
// Web Locks also coordinates tabs on the same origin. Never queue a stale clipboard write.
let copying = false
const busy = () => new Error('其他报价单正在复制，请等待完成后重新点击复制')
export async function withQuoteSheetCopyLock(write: () => Promise<void>) {
  if (copying) throw busy()
  copying = true
  try {
    if (navigator.locks?.request) {
      await navigator.locks.request('quotation-customer-sheet-clipboard', { ifAvailable: true }, async lock => {
        if (!lock) throw busy()
        await write()
      })
    } else await write()
  } finally { copying = false }
}
