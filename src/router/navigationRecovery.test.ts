// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import PageLoadNotice from '@/components/PageLoadNotice.vue'
import { failedNavigation, installNavigationRecovery, isPageResourceError, reloadFailedPage } from './navigationRecovery'

afterEach(() => { failedNavigation.value = ''; document.body.innerHTML = ''; vi.restoreAllMocks() })

describe('page resource failures after a release', () => {
  it.each([
    'Failed to fetch dynamically imported module: https://example.test/assets/old.js',
    'error loading dynamically imported module: https://example.test/assets/old.js',
    'Importing a module script failed.',
    'Loading chunk 123 failed.',
    'Unable to preload CSS for /assets/old.css',
  ])('recognizes supported browser resource errors: %s', (message) => {
    expect(isPageResourceError(new TypeError(message))).toBe(true)
  })

  it('keeps the current form and offers recovery for the failed target, without reloading automatically', async () => {
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', component: { render: () => h('input', { 'aria-label': '未保存内容', value: '保留的客户信息' }) } },
      { path: '/quotation/records', component: () => Promise.reject(new TypeError('Failed to fetch dynamically imported module: /assets/old.js')) },
    ] })
    const dispose = installNavigationRecovery(router)
    const host = document.createElement('div'); document.body.append(host)
    const app = createApp({ render: () => h('main', [h(PageLoadNotice), h(RouterView)]) }).use(router)
    await router.push('/'); await router.isReady(); app.mount(host)
    try {
      await expect(router.push('/quotation/records?record=sample#details')).rejects.toThrow('dynamically imported')
      await nextTick()
      expect(router.currentRoute.value.path).toBe('/')
      expect(host.querySelector('input')?.value).toBe('保留的客户信息')
      expect(host.querySelector('[role="alert"]')?.textContent).toContain('请先保留未保存的内容')
      expect(assign).not.toHaveBeenCalled()
      host.querySelector('button')!.click()
      expect(assign).toHaveBeenCalledExactlyOnceWith('/quotation/records?record=sample#details')
    } finally { app.unmount(); dispose() }
  })

  it('clears the notice after successful navigation and ignores unrelated application errors', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', component: { render: () => null } },
      { path: '/broken', component: () => Promise.reject(new Error('Business validation failed')) },
    ] })
    const dispose = installNavigationRecovery(router)
    try {
      failedNavigation.value = '/old'
      await router.push('/')
      expect(failedNavigation.value).toBe('')
      await expect(router.push('/broken')).rejects.toThrow('Business validation failed')
      expect(failedNavigation.value).toBe('')
    } finally { dispose() }
  })

  it.each(['https://outside.test/', '//outside.test/', '/\\outside.test/', 'javascript:alert(1)'])('rejects an unsafe recovery target: %s', (target) => {
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    failedNavigation.value = target
    reloadFailedPage()
    expect(assign).not.toHaveBeenCalled()
  })
})
