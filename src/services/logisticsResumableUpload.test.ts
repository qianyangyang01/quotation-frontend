import { beforeEach, describe, expect, it, vi } from 'vitest'
import { resumableLogisticsUpload } from './logisticsResumableUpload'
import { api, ApiError, uploadForm } from './http'

vi.mock('@/data/authStore', () => ({ currentAuthUser: { value: { account: 'USER' } } }))
vi.mock('./http', async original => {
  const actual = await original<typeof import('./http')>()
  return { ...actual, api: { post: vi.fn() }, uploadForm: vi.fn() }
})
const storage = new Map<string, string>()
const chunkBytes = 4 * 1024 * 1024
beforeEach(() => {
  vi.clearAllMocks(); storage.clear()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
    removeItem: (key: string) => storage.delete(key),
  })
})
describe('durable logistics upload', () => {
  it('uploads two files concurrently and cancels both requests', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 'session', chunkBytes, received: [[], [], []] })
    const cancellations: Array<ReturnType<typeof vi.fn>> = []
    vi.mocked(uploadForm).mockImplementation(() => {
      let reject!: (error: Error) => void
      const promise = new Promise((_, fail) => { reject = fail })
      const cancel = vi.fn(() => reject(new DOMException('paused', 'AbortError')))
      cancellations.push(cancel)
      return { promise, cancel }
    })
    const task = resumableLogisticsUpload('dataset', ['a', 'b', 'c'].map(name => new File(['x'], name + '.xlsx')), false, 'key')
    const result = expect(task.promise).rejects.toMatchObject({ name: 'AbortError' })
    await vi.waitFor(() => expect(uploadForm).toHaveBeenCalledTimes(2))
    task.cancel()
    await result
    expect(cancellations.every(cancel => cancel.mock.calls.length > 0)).toBe(true)
    expect(uploadForm).toHaveBeenCalledTimes(2)
    expect(storage.size).toBe(1)
  })
  it('keeps identity across interrupted runs and sends only the missing chunk', async () => {
    const file = new File([new Uint8Array(chunkBytes + 3)], '花海.xlsx')
    const post = vi.mocked(api.post)
    post.mockResolvedValueOnce({ id: 'session', chunkBytes, received: [[]] })
    vi.mocked(uploadForm).mockImplementation(() => ({ promise: Promise.reject(new ApiError('offline', 0, 'NETWORK', 'req')), cancel: vi.fn() }))
    await expect(resumableLogisticsUpload('dataset', [file], true, 'key-original').promise).rejects.toThrow('已保存上传进度')
    expect(storage.size).toBe(1)
    post.mockResolvedValueOnce({ id: 'session', chunkBytes, received: [['hash']] }).mockResolvedValueOnce({ id: 'batch' })
    vi.mocked(uploadForm).mockReturnValue({ promise: Promise.resolve({}), cancel: vi.fn() })
    vi.mocked(uploadForm).mockClear()
    const progress = vi.fn()
    await expect(resumableLogisticsUpload('dataset', [file], true, 'key-new', progress).promise).resolves.toEqual({ id: 'batch' })
    expect(post.mock.calls[1]![2]).toBe('key-original')
    expect(uploadForm).toHaveBeenCalledTimes(1)
    expect(vi.mocked(uploadForm).mock.calls[0]![0]).toContain('/chunks/1')
    expect((vi.mocked(uploadForm).mock.calls[0]![1].get('chunk') as Blob).size).toBe(3)
    expect(progress.mock.calls.find(call => call[0].phase === 'uploading')![0].loaded).toBe(chunkBytes)
    expect(storage.size).toBe(0)
  })
  it('starts a new session when the server has expired an old resume key', async () => {
    vi.mocked(api.post).mockRejectedValueOnce(new ApiError('expired', 410, 'UPLOAD_EXPIRED', 'req'))
      .mockResolvedValueOnce({ id: 'session', chunkBytes, received: [], batch: { id: 'batch' } })
    await expect(resumableLogisticsUpload('dataset', [new File(['a'], 'a.xlsx')], false, 'old-key').promise).resolves.toEqual({ id: 'batch' })
    expect(vi.mocked(api.post).mock.calls[1]![2]).not.toBe('old-key')
  })
  it('recovers a lost completion response without uploading again', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 'session', chunkBytes, received: [], batch: { id: 'batch' } })
    await expect(resumableLogisticsUpload('dataset', [new File(['a'], 'a.xlsx')], false, 'key').promise).resolves.toEqual({ id: 'batch' })
    expect(uploadForm).not.toHaveBeenCalled()
  })
  it('cancels before submitting any request and never retries permission failures', async () => {
    const task = resumableLogisticsUpload('dataset', [new File(['a'], 'a.xlsx')], false, 'key')
    task.cancel()
    await expect(task.promise).rejects.toMatchObject({ name: 'AbortError' })
    expect(api.post).not.toHaveBeenCalled()
    vi.mocked(api.post).mockResolvedValue({ id: 'session', chunkBytes, received: [[]] })
    vi.mocked(uploadForm).mockImplementation(() => ({ promise: Promise.reject(new ApiError('denied', 403, 'FORBIDDEN', 'req')), cancel: vi.fn() }))
    await expect(resumableLogisticsUpload('dataset', [new File(['a'], 'a.xlsx')], false, 'key').promise).rejects.toThrow('denied')
    expect(uploadForm).toHaveBeenCalledTimes(1)
  })
})
