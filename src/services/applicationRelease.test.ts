// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'
import { createAppUpdateMonitor } from './appUpdate'
import { currentApplicationRelease, parseApplicationRelease } from './applicationRelease'
const identity = { releaseBuildId: 'loaded', requiredEpoch: 'epoch-a' }
const manifest = (releaseBuildId = 'next', policy = 'optional', requiredEpoch = 'epoch-a') => new Response(JSON.stringify({ schemaVersion: 1, buildId: 'compatible', releaseBuildId, release: { ...currentApplicationRelease, policy, requiredEpoch } }))

describe('release-aware update policy', () => {
  it('announces ordinary releases without blocking saves or in-flight operations', async () => {
    const monitor = createAppUpdateMonitor('compatible', vi.fn(() => Promise.resolve(manifest())), identity)
    const finish = monitor.beginOperation()
    await expect(monitor.ensureCurrent()).resolves.toBeUndefined()
    expect(monitor.state.updateAvailable).toBe(true)
    expect(monitor.state.updateRequired).toBe(false)
    expect(monitor.state.activeOperations).toBe(1)
    expect(monitor.state.release.features.length).toBeGreaterThan(0)
    finish(); finish()
    expect(monitor.state.activeOperations).toBe(0)
  })
  it.each([['required', 'epoch-b'], ['optional', 'epoch-b']])('requires an update for %s releases across a critical boundary, including skipped releases', async (policy, epoch) => {
    const monitor = createAppUpdateMonitor('compatible', vi.fn(() => Promise.resolve(manifest('next', policy, epoch))), identity)
    const finish = monitor.beginOperation()
    await expect(monitor.ensureCurrent()).rejects.toMatchObject({ code: 'CLIENT_UPDATE_REQUIRED' })
    expect(monitor.state.activeOperations).toBe(1)
    finish()
    expect(monitor.state.activeOperations).toBe(0)
  })
  it('does not gate a freshly loaded critical release', async () => {
    const monitor = createAppUpdateMonitor('compatible', vi.fn(() => Promise.resolve(manifest('loaded', 'required', 'epoch-a'))), identity)
    await expect(monitor.ensureCurrent()).resolves.toBeUndefined()
    expect(monitor.state.updateAvailable).toBe(false)
  })
  it('clears an optional reminder on rollback to the loaded artifact, but never clears a critical gate', async () => {
    const fetcher = vi.fn(() => Promise.resolve(manifest()))
    const monitor = createAppUpdateMonitor('compatible', fetcher, identity)
    await monitor.check(); expect(monitor.state.updateAvailable).toBe(true)
    fetcher.mockImplementation(() => Promise.resolve(manifest('loaded')))
    await monitor.check(); expect(monitor.state.updateAvailable).toBe(false)
    monitor.requireUpdate('exchange-rate')
    await monitor.check(); expect(monitor.state.updateRequired).toBe(true)
    expect(monitor.state.reason).toBe('exchange-rate')
  })
  it('does not turn malformed metadata into a forced update or discard already verified notes during an outage', async () => {
    const fetcher = vi.fn(() => Promise.resolve(manifest()))
    const monitor = createAppUpdateMonitor('compatible', fetcher, identity)
    await monitor.check()
    fetcher.mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ schemaVersion: 1, buildId: 'compatible', releaseBuildId: 'bad', release: { policy: 'required' } }))))
    await monitor.check()
    expect(monitor.state.updateRequired).toBe(false)
    expect(monitor.state.updateAvailable).toBe(true)
    expect(monitor.state.unavailable).toBe(true)
    expect(monitor.state.release.version).toBe(currentApplicationRelease.version)
  })
  it('validates bounded plain-text release content', () => {
    expect(parseApplicationRelease(currentApplicationRelease)).toBeDefined()
    expect(parseApplicationRelease({ ...currentApplicationRelease, features: [] })).toBeUndefined()
    expect(parseApplicationRelease({ ...currentApplicationRelease, policy: 'silent' })).toBeUndefined()
    expect(parseApplicationRelease({ ...currentApplicationRelease, requiredEpoch: '' })).toBeUndefined()
  })
})
