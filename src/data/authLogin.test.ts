import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const dependencies = vi.hoisted(() => ({
  post: vi.fn(),
  resetCsrf: vi.fn(),
  hydrateFinanceSettings: vi.fn(),
  clearFinanceSettingsCache: vi.fn(),
  clearPublishedLogisticsCache: vi.fn(),
}))

vi.mock('@/services/http', () => ({ api: { post: dependencies.post }, resetCsrf: dependencies.resetCsrf }))
vi.mock('@/services/financeSettings', () => ({
  hydrateFinanceSettings: dependencies.hydrateFinanceSettings,
  clearFinanceSettingsCache: dependencies.clearFinanceSettingsCache,
}))
vi.mock('@/data/publishedLogisticsRepository', () => ({ clearPublishedLogisticsCache: dependencies.clearPublishedLogisticsCache }))

import { authState, login } from './authStore'

const session = {
  id: 'user-1',
  name: '钱洋洋',
  account: 'QYY001',
  role: 'finance',
  mustChangePassword: false,
  permissions: ['finance', 'quote'],
}

describe('login finance settings bootstrap', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    authState.current = null
    authState.permissions = []
    authState.initialized = false
    dependencies.post.mockResolvedValue(session)
  })

  afterEach(() => vi.useRealTimers())

  it('does not finish login navigation data until finance hydration settles', async () => {
    let resolveHydration!: () => void
    dependencies.hydrateFinanceSettings.mockReturnValue(new Promise<void>(resolve => { resolveHydration = resolve }))

    let settled = false
    const pending = login('qyy001', 'secret').then(result => { settled = true; return result })
    await Promise.resolve()
    expect(dependencies.post).toHaveBeenCalledWith('/auth/login', { account: 'QYY001', password: 'secret' })
    expect(dependencies.hydrateFinanceSettings).toHaveBeenCalledOnce()
    expect(settled).toBe(false)

    resolveHydration()
    await expect(pending).resolves.toMatchObject({ ok: true, financeSettingsReady: true })
  })

  it('keeps authentication successful when finance hydration fails', async () => {
    dependencies.hydrateFinanceSettings.mockRejectedValue(new Error('财务接口不可用'))

    await expect(login('QYY001', 'secret')).resolves.toMatchObject({
      ok: true,
      financeSettingsReady: false,
      financeSettingsMessage: '财务接口不可用',
    })
    expect(authState.current?.account).toBe('QYY001')
  })

  it('does not hydrate finance settings when authentication fails', async () => {
    dependencies.post.mockRejectedValue(new Error('账号或密码错误'))

    await expect(login('QYY001', 'wrong')).resolves.toEqual({ ok: false, message: '账号或密码错误' })
    expect(dependencies.hydrateFinanceSettings).not.toHaveBeenCalled()
  })

  it('continues login after the bounded finance hydration attempt times out', async () => {
    vi.useFakeTimers()
    dependencies.hydrateFinanceSettings.mockImplementation(({ signal }: { signal: AbortSignal }) => new Promise<void>((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
    }))

    const pending = login('QYY001', 'secret')
    await vi.advanceTimersByTimeAsync(8_000)
    await expect(pending).resolves.toMatchObject({
      ok: true,
      financeSettingsReady: false,
      financeSettingsMessage: '财务设置加载超时，请进入财务页面重试',
    })
  })
})
