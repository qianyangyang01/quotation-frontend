import { afterEach, describe, expect, it, vi } from 'vitest'
import { uploadForm } from './http'
import { createPurchaseImportJob } from './purchaseAsyncImports'

vi.mock('./http', () => ({ api: {}, idempotencyKey: vi.fn(), uploadForm: vi.fn() }))
afterEach(() => vi.clearAllMocks())

describe('direct purchase workbook upload', () => {
  it('uploads the original file without reading or repacking it and preserves progress and cancellation', () => {
    const file = new File(['original workbook bytes'], '固定采购表.XLSX', { lastModified: 123 })
    const read = vi.spyOn(file, 'arrayBuffer')
    const onProgress = vi.fn()
    const request = { promise: Promise.resolve({ id: 'job-1' }), cancel: vi.fn() }
    vi.mocked(uploadForm).mockReturnValue(request)
    expect(createPurchaseImportJob(file, onProgress)).toBe(request)
    const [path, form, progress] = vi.mocked(uploadForm).mock.calls[0]!
    expect(path).toBe('/purchase-imports/jobs')
    expect(form.get('file')).toBe(file)
    expect(form.get('importMode')).toBe('text-only')
    expect(form.has('originalSizeBytes')).toBe(false)
    expect(form.has('removedMediaCount')).toBe(false)
    expect(progress).toBe(onProgress)
    expect(read).not.toHaveBeenCalled()
  })

  it('rejects wrong extensions, empty files and oversized files before starting an upload', () => {
    expect(() => createPurchaseImportJob(new File(['data'], 'table.csv'))).toThrow('.xlsx')
    expect(() => createPurchaseImportJob(new File([], 'empty.xlsx'))).toThrow('不能为空')
    const large = new File(['data'], 'large.xlsx')
    Object.defineProperty(large, 'size', { value: 100 * 1024 * 1024 + 1 })
    expect(() => createPurchaseImportJob(large)).toThrow('100MB')
    expect(uploadForm).not.toHaveBeenCalled()
  })

  it('marks optimized uploads as 2026 legacy data and sends cleanup metadata', () => {
    const file = new File(['optimized data'], '陈晨.xlsx')
    const request = { promise: Promise.resolve({ id: 'legacy-job' }), cancel: vi.fn() }
    vi.mocked(uploadForm).mockReturnValue(request)
    createPurchaseImportJob(file, { importProfile: 'legacy-2026', originalSizeBytes: 724_000_000, removedMediaCount: 1436 })
    const form = vi.mocked(uploadForm).mock.calls[0]![1]
    expect(form.get('importProfile')).toBe('legacy-2026')
    expect(form.get('originalSizeBytes')).toBe('724000000')
    expect(form.get('removedMediaCount')).toBe('1436')
  })
})
