import { afterEach, expect, it, vi } from 'vitest'
import { copyQuoteSheetData } from './customerQuoteSheetClipboard'
import type { CustomerQuoteSheet } from '@/data/customerQuoteSheet'

const sheet: CustomerQuoteSheet = {
  agent: 'Alex', date: '12 Sep 2026', issues: [], quantityLabels: ['1 pc', '2 pcs', '3 pcs', '5 pcs'],
  rows: [{ key: 'a', number: 1, country: 'US', provider: 'Yanwen', shippingTime: '6-12 days', prices: [12.8, null, 28.2, 43.6], sourceDescription: 'private' }],
}
afterEach(() => vi.unstubAllGlobals())
it('writes tabular text with headers and preserves clipboard rejection', async () => {
  vi.stubGlobal('isSecureContext', true)
  const writeText = vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('navigator', { clipboard: { writeText } })
  await copyQuoteSheetData(sheet)
  expect(writeText.mock.calls[0][0]).toContain('1\t—\tUS\tYanwen\t6-12 days\t1-2 days\t$12.80\t—')
  expect(writeText.mock.calls[0][0]).not.toContain('private')
  writeText.mockRejectedValue(new Error('denied'))
  await expect(copyQuoteSheetData(sheet)).rejects.toThrow('未复制成功')
})
it('reports unsupported clipboard without fallback writes', async () => {
  vi.stubGlobal('isSecureContext', false)
  await expect(copyQuoteSheetData(sheet)).rejects.toThrow('不支持复制数据')
})
