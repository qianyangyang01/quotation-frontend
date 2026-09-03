export const LOGISTICS_PAGE_SIZES = [20, 50, 100] as const

export function logisticsPageSize(value: unknown) {
  const size = Number(value)
  return LOGISTICS_PAGE_SIZES.includes(size as 20 | 50 | 100) ? size : 20
}

export function logisticsPageFromQuery(value: unknown) {
  const page = Number(value)
  return Number.isFinite(page) && page > 0 ? Math.trunc(page) - 1 : 0
}

export function logisticsPageQuery(page: number, size: number) {
  return { page: String(Math.max(0, Math.trunc(page)) + 1), size: String(logisticsPageSize(size)) }
}

export function logisticsPageNumbers(page: number, totalPages: number, windowSize = 5) {
  const lastPage = Math.max(1, totalPages)
  const current = Math.min(lastPage, Math.max(1, page + 1))
  const width = Math.max(1, Math.min(windowSize, lastPage))
  let start = Math.max(1, current - Math.floor(width / 2))
  start = Math.min(start, lastPage - width + 1)
  return Array.from({ length: width }, (_, index) => start + index)
}

export function clampLogisticsPage(page: number, totalPages: number) {
  return Math.min(Math.max(0, Math.trunc(page)), Math.max(0, totalPages - 1))
}

export function logisticsPageRange(page: number, size: number, total: number) {
  if (total <= 0) return { from: 0, to: 0 }
  const safeSize = Math.max(1, Math.trunc(size))
  const safePage = Math.max(0, Math.trunc(page))
  return { from: safePage * safeSize + 1, to: Math.min(total, (safePage + 1) * safeSize) }
}
