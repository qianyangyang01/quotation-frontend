import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
vi.mock('./http', () => ({ api }))

import { createSupplierRecord, deleteSupplierRecord, loadSupplierRecords, updateSupplierRecord, type SupplierRecordInput } from './supplierRecords'

const input: SupplierRecordInput = {
  name: '广州华盛服饰有限公司', industryBelt: '广州·十三行', contactRole: '业务经理', relationshipNotes: '长期合作',
  invoiceType: '普票', taxPoint: 0.03, qualityGrade: 'A', deliveryTerms: '3-7天', capacityOrder: '5000件/天',
  stockingStrategy: '安全库存备货', alternativeInquiry: '已询价3家', costSheet: '已提供', hotProductRecommendation: true,
  freeSample: true, afterSales: '支持7天内退换', cooperationScore: 92, rating: 'A级', monthlyPurchaseAmount: 256800,
  notes: '响应快', suggestion: '保持合作',
}

describe('supplier record API', () => {
  beforeEach(() => vi.clearAllMocks())

  it('loads a filtered page through the independent endpoint', async () => {
    api.get.mockResolvedValue({ items: [], page: 1, size: 20, total: 0, totalPages: 0 })
    await loadSupplierRecords({ query: ' 华盛 ', industryBelt: '广州', rating: 'A级', page: 1, size: 20 })
    expect(api.get).toHaveBeenCalledWith('/supplier-records?query=%E5%8D%8E%E7%9B%9B&industryBelt=%E5%B9%BF%E5%B7%9E&rating=A%E7%BA%A7&page=1&size=20')
  })

  it('creates without any product or quotation identifier', async () => {
    api.post.mockResolvedValue({ id: 'record-1' })
    await createSupplierRecord(input)
    expect(api.post).toHaveBeenCalledWith('/supplier-records', input)
    expect(input).not.toHaveProperty('sku')
    expect(input).not.toHaveProperty('productId')
    expect(input).not.toHaveProperty('quotationId')
  })

  it('uses optimistic locking for update and delete', async () => {
    api.put.mockResolvedValue({ id: 'record-1', version: 5 })
    api.delete.mockResolvedValue(undefined)
    const record = { id: 'record-1', version: 4 }
    await updateSupplierRecord(record, input)
    await deleteSupplierRecord(record)
    expect(api.put).toHaveBeenCalledWith('/supplier-records/record-1', input, { 'If-Match': '4' })
    expect(api.delete).toHaveBeenCalledWith('/supplier-records/record-1', { 'If-Match': '4' })
  })
})
