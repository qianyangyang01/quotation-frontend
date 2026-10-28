// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { captureRecoveryPhotos, restoreRecoveryPhotos, repriceRecoveryAverages } from './quoteSheetRecovery'
import { loadQuotePhotos, type QuoteLocalPhoto } from './quoteLocalPhotos'
import { quoteSheetRowKey, type QuoteSheetSourceRow } from '@/data/customerQuoteSheet'
import type { AveragePlan } from '@/data/quoteChannelAverage'
vi.mock('./quoteLocalPhotos', async original => ({ ...await original<typeof import('./quoteLocalPhotos')>(), loadQuotePhotos: vi.fn() }))
afterEach(() => vi.restoreAllMocks())

it('backs up local image bytes and restores a fresh file without uploading or relying on old blob URLs', async () => {
  const file = new File(['image-bytes'], 'product.png', { type: 'image/png' })
  const local = { file, name: file.name, url: 'blob:old', image: document.createElement('img'), assetId: 'saved-asset' }
  const snapshot = await captureRecoveryPhotos([local])
  expect(snapshot[0]!.data).toBe('data:image/png;base64,aW1hZ2UtYnl0ZXM=')
  expect(JSON.stringify(snapshot)).not.toContain('blob:old')
  vi.mocked(loadQuotePhotos).mockImplementation(async files => files.map(file => ({ file, name: file.name, url: 'blob:new', image: document.createElement('img') })))
  const restored = await restoreRecoveryPhotos(snapshot)
  expect(restored[0]!.assetId).toBe('saved-asset')
  expect(restored[0]!.file!.size).toBe(file.size)
  expect(restored[0]!.file!.type).toBe(file.type)
  expect(restored[0]!.url).toBe('blob:new')
  await expect(captureRecoveryPhotos([{ ...local, file: undefined } as QuoteLocalPhoto])).rejects.toThrow('尚未就绪')
})

it('never restores arbitrary URLs as images', async () => {
  await expect(restoreRecoveryPhotos([{ name: 'bad', type: 'image/png', data: 'https://example.com/private' }])).rejects.toThrow('格式无效')
})

it('retains deliberate average edits even when the entered value equalled the old system amount', () => {
  const rows = ['a', 'b'].map(channelKey => ({ country: 'US', channelKey }) as QuoteSheetSourceRow)
  const plan: AveragePlan = { id: 'average', provider: 'Combined Shipping', shippingTime: '', mode: 'weighted', display: 'summary', quantities: [1],
    members: rows.map(row => ({ optionId: quoteSheetRowKey(row), weight: 50, sourcePrices: [10] })), systemPrices: [10], prices: [10] }
  const original = JSON.stringify(plan)
  expect(repriceRecoveryAverages([plan], rows, [1], () => 20).restored[0]!.prices).toEqual([20])
  expect(repriceRecoveryAverages([plan], rows, [1], () => 20, { 'average:1': '10.00' }).restored[0]).toMatchObject({ systemPrices: [20], prices: [10] })
  expect(JSON.stringify(plan)).toBe(original)
})
