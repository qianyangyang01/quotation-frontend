import { describe, expect, it } from 'vitest'
import { createPurchaseImportTaskRequests } from './purchaseImportTaskRequests'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

describe('purchase import task response ownership', () => {
  it('keeps the newer selected task when an earlier task detail finishes last', async () => {
    const requests = createPurchaseImportTaskRequests()
    const slow = deferred<string>()
    let selected = ''
    requests.select('A')
    const old = requests.read('detail', 'A', () => slow.promise, value => { selected = value })
    requests.select('B')
    await requests.read('detail', 'B', async () => 'task B', value => { selected = value })
    slow.resolve('task A')
    expect(await old).toBe(false)
    expect(selected).toBe('task B')
  })

  it('does not attach slow duplicate choices to a different task or increase its confirmation count', async () => {
    const requests = createPurchaseImportTaskRequests()
    const slow = deferred<string[]>()
    let groups: string[] = []
    requests.select('A')
    const old = requests.read('duplicates', 'A', () => slow.promise, value => { groups = value })
    requests.select('B')
    await requests.read('duplicates', 'B', async () => [], value => { groups = value })
    slow.resolve(['SKU-from-A'])
    expect(await old).toBe(false)
    expect(groups).toEqual([])
  })

  it('rejects an earlier visit response after selecting A, B, then A again', async () => {
    const requests = createPurchaseImportTaskRequests()
    const slow = deferred<string>()
    let selected = ''
    requests.select('A')
    const old = requests.read('detail', 'A', () => slow.promise, value => { selected = value })
    requests.select('B')
    requests.select('A')
    await requests.read('detail', 'A', async () => 'current A', value => { selected = value })
    slow.resolve('outdated A')
    expect(await old).toBe(false)
    expect(selected).toBe('current A')
  })

  it('keeps the newest row page when page responses arrive out of order', async () => {
    const requests = createPurchaseImportTaskRequests()
    const slow = deferred<number>()
    let page = 0
    requests.select('A')
    const old = requests.read('rows', 'A', () => slow.promise, value => { page = value })
    await requests.read('rows', 'A', async () => 2, value => { page = value })
    slow.resolve(1)
    expect(await old).toBe(false)
    expect(page).toBe(2)
  })

  it('allows independent details and duplicate requests for the same task', async () => {
    const requests = createPurchaseImportTaskRequests()
    const slow = deferred<string>()
    let details = ''
    let groups: string[] = []
    requests.select('A')
    const detail = requests.read('detail', 'A', () => slow.promise, value => { details = value })
    expect(await requests.read('duplicates', 'A', async () => ['SKU-A'], value => { groups = value })).toBe(true)
    slow.resolve('task A')
    expect(await detail).toBe(true)
    expect(details).toBe('task A')
    expect(groups).toEqual(['SKU-A'])
  })

  it('does not let a stale task follow-up cancel a current task request', async () => {
    const requests = createPurchaseImportTaskRequests()
    const slow = deferred<string>()
    let selected = ''
    requests.select('B')
    const current = requests.read('detail', 'B', () => slow.promise, value => { selected = value })
    expect(await requests.read('detail', 'A', async () => 'old A', value => { selected = value })).toBe(false)
    slow.resolve('task B')
    expect(await current).toBe(true)
    expect(selected).toBe('task B')
  })

  it('ignores stale request errors while preserving errors for the current task', async () => {
    const requests = createPurchaseImportTaskRequests()
    const slow = deferred<string>()
    requests.select('A')
    const old = requests.read('detail', 'A', () => slow.promise, () => {})
    requests.select('B')
    slow.reject(new Error('old task failed'))
    expect(await old).toBe(false)
    await expect(requests.read('detail', 'B', async () => { throw new Error('current failed') }, () => {})).rejects.toThrow('current failed')
  })
})
