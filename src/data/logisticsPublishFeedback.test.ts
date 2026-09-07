import { describe, expect, it, vi } from 'vitest'
import { createLogisticsPublishFeedback } from './logisticsPublishFeedback'

describe('logistics publish feedback', () => {
  it('uses committed channel counts and ignores a late progress response after completion', async () => {
    vi.useFakeTimers()
    const flow = createLogisticsPublishFeedback()
    try {
      let finishSubmit!: (value: number) => void, finishPoll!: (value: number) => void
      const read = vi.fn().mockResolvedValueOnce(2).mockImplementationOnce(() => new Promise<number>(resolve => { finishPoll = resolve }))
      const task = flow.execute(() => new Promise<number>(resolve => { finishSubmit = resolve }), () => {}, async () => {}, { total: 5, read })
      await vi.advanceTimersByTimeAsync(500)
      expect(flow.completed.value).toBe(2)
      expect(flow.total.value).toBe(5)
      await vi.advanceTimersByTimeAsync(1500)
      finishSubmit(5)
      await task
      finishPoll(3)
      await vi.advanceTimersByTimeAsync(5000)
      expect(flow.completed.value).toBe(5)
      expect(flow.phase.value).toBe('done')
      expect(read).toHaveBeenCalledTimes(2)
      expect(vi.getTimerCount()).toBe(0)
    } finally { flow.stop(); vi.useRealTimers() }
  })
  it('shows the confirmed result before a slow refresh completes and rejects duplicate clicks', async () => {
    const flow = createLogisticsPublishFeedback()
    let finishSubmit!: (value: number) => void, finishRefresh!: () => void
    const submit = vi.fn(() => new Promise<number>(resolve => { finishSubmit = resolve }))
    const refresh = vi.fn(() => new Promise<void>(resolve => { finishRefresh = resolve }))
    const accept = vi.fn()
    const task = flow.execute(submit, accept, refresh)
    expect(flow.phase.value).toBe('publishing')
    await flow.execute(submit, accept, refresh)
    expect(submit).toHaveBeenCalledTimes(1)
    finishSubmit(88)
    await Promise.resolve()
    expect(accept).toHaveBeenCalledWith(88)
    expect(flow.phase.value).toBe('refreshing')
    finishRefresh()
    await task
    expect(flow.phase.value).toBe('done')
  })
  it('does not report a confirmed publication as failed when refresh fails', async () => {
    const flow = createLogisticsPublishFeedback(), accept = vi.fn()
    await flow.execute(async () => 88, accept, async () => { throw new Error('offline') })
    expect(accept).toHaveBeenCalledWith(88)
    expect(flow.phase.value).toBe('done')
    expect(flow.detail.value).toContain('无需重复发布')
  })
  it('keeps interrupted requests unconfirmed without automatically resubmitting', async () => {
    const flow = createLogisticsPublishFeedback(), accept = vi.fn(), refresh = vi.fn()
    const submit = vi.fn(async () => { throw new Error('network error') })
    await flow.execute(submit, accept, refresh)
    expect(flow.phase.value).toBe('unconfirmed')
    expect(flow.detail.value).toContain('部分渠道可能已经发布')
    expect(submit).toHaveBeenCalledTimes(1)
    expect(accept).not.toHaveBeenCalled()
    expect(refresh).not.toHaveBeenCalled()
  })
})
