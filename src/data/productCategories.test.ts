import { describe, expect, it } from 'vitest'
import { quotationProductCategories } from './productCategories'

describe('quotation product categories', () => {
  it('keeps the approved 27-category order without duplicates', () => {
    expect(quotationProductCategories).toEqual([
      '文胸', '袜子', '内裤', '服装', '化妆品', '保健品', '日用品',
      '庭院工具', '家用电器', '健身器材', '厨房用具', '家纺', '配饰',
      '鞋', '文具', '灯具', '数码', '辅料', '玩具', '书籍', '宠物用品',
      '医疗', '汽车用品', '清洁用品', '箱包', '护肤品', '其他',
    ])
    expect(new Set(quotationProductCategories).size).toBe(27)
  })
})
