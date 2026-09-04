import type { Component } from 'vue'

export type RouteViewLoader = () => Promise<{ default: Component }>

const jerryModuleView: RouteViewLoader = () => import('@/views/JerryModuleView.vue')
const quotationRecordsView: RouteViewLoader = () => import('@/views/QuotationRecordsView.vue')

export const routeViewLoaders: Record<string, RouteViewLoader> = {
  '/login': () => import('@/views/LoginView.vue'),
  '/change-password': () => import('@/views/ChangePasswordView.vue'),
  '/quotation/overview': () => import('@/views/QuotationOverviewView.vue'),
  '/quotation': () => import('@/views/QuotationSystemView.vue'),
  '/quotation/products': jerryModuleView,
  '/quotation/logistics': () => import('@/views/LogisticsWorkspaceView.vue'),
  '/quotation/members': jerryModuleView,
  '/quotation/my-records': quotationRecordsView,
  '/quotation/records': quotationRecordsView,
  '/quotation/permissions': () => import('@/views/PermissionManagementView.vue'),
}

const warmed = new Set<RouteViewLoader>()

export function routeView(path: string) {
  const loader = routeViewLoaders[path]
  if (!loader) throw new Error(`No route view registered for ${path}`)
  return loader
}

export function prefetchRouteView(path: string, loaders = routeViewLoaders) {
  const loader = loaders[path]
  if (!loader || warmed.has(loader)) return
  warmed.add(loader)
  void loader().catch(() => warmed.delete(loader))
}

export function scheduleRouteViewPrefetch(paths: string[], delayMs = 1200) {
  if (typeof window === 'undefined') return () => undefined
  const connection = (navigator as Navigator & { connection?: { saveData?: boolean; effectiveType?: string } }).connection
  if (connection?.saveData || connection?.effectiveType === 'slow-2g' || connection?.effectiveType === '2g') return () => undefined
  let cancelled = false
  const uniquePaths = [...new Set(paths)]
  const warm = () => {
    if (cancelled) return
    uniquePaths.forEach((path, index) => window.setTimeout(() => {
      if (!cancelled) prefetchRouteView(path)
    }, index * 120))
  }
  const idleWindow = window as Window & { requestIdleCallback?: (callback: () => void, options?: { timeout: number }) => number; cancelIdleCallback?: (id: number) => void }
  if (idleWindow.requestIdleCallback) {
    const id = idleWindow.requestIdleCallback(warm, { timeout: delayMs })
    return () => { cancelled = true; idleWindow.cancelIdleCallback?.(id) }
  }
  const id = window.setTimeout(warm, delayMs)
  return () => { cancelled = true; window.clearTimeout(id) }
}
