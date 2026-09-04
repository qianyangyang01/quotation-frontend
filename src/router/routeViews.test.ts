import { describe, expect, it, vi } from 'vitest'
import { prefetchRouteView } from './routeViews'

describe('route view prefetch', () => {
  it('warms a route only once', async () => {
    const loader = vi.fn().mockResolvedValue({ default: {} })
    const loaders = { '/quotation': loader }

    prefetchRouteView('/quotation', loaders)
    prefetchRouteView('/quotation', loaders)
    await Promise.resolve()

    expect(loader).toHaveBeenCalledTimes(1)
  })

  it('allows a failed warmup to be retried', async () => {
    const loader = vi.fn()
      .mockRejectedValueOnce(new Error('temporary chunk failure'))
      .mockResolvedValueOnce({ default: {} })
    const loaders = { '/quotation/records': loader }

    prefetchRouteView('/quotation/records', loaders)
    await Promise.resolve()
    await Promise.resolve()
    prefetchRouteView('/quotation/records', loaders)
    await Promise.resolve()

    expect(loader).toHaveBeenCalledTimes(2)
  })

  it('ignores paths without a registered view', () => {
    expect(() => prefetchRouteView('/missing', {})).not.toThrow()
  })
})
