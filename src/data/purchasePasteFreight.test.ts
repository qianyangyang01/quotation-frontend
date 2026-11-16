import { describe, expect, it } from 'vitest'
import { applyPurchasePaste, emptyPurchasePasteRow, inferredPasteFreeShipping, PURCHASE_PASTE_COLUMNS, SPECIAL_UNIFIED_PURCHASE_PASTE_COLUMNS, validatePurchasePaste } from './purchasePaste'

describe.each([{columns:PURCHASE_PASTE_COLUMNS}, {columns:SPECIAL_UNIFIED_PURCHASE_PASTE_COLUMNS}])('freight-derived paste flag', ({columns}) => {
  const row = (freight: string[], flag = '') => {
    const values: Record<string,string> = {sku:'AZ2603605',singleFreightCny:freight[0]!,freight10Cny:freight[1]!,freight100Cny:freight[2]!,freeShipping:flag}
    return columns.map(([, field]) => values[field] || '')
  }
  it.each([
    [['6','11','56'],'否'], [['3','0','0'],'否'], [['0','3','0'],'否'], [['0','0','3'],'否'],
    [['0.01','',''],'否'], [['0','0','0'],'是'], [['0.00',' 0 ','.0'],'是'],
  ] as const)('uses %j to derive %s without altering freight values', (freight, expected) => {
    const input = row([...freight],expected === '是' ? '否' : '是')
    const pasted = applyPurchasePaste([emptyPurchasePasteRow(columns)],[input],0,0,columns)[0]!
    expect(inferredPasteFreeShipping(pasted,columns)).toBe(expected)
    expect(pasted[columns.findIndex(c=>c[1]==='freeShipping')]).toBe(expected)
    for (const field of ['singleFreightCny','freight10Cny','freight100Cny']) {
      const i=columns.findIndex(c=>c[1]===field); expect(pasted[i]).toBe(input[i])
    }
    expect(validatePurchasePaste([input],true,columns).records[0]?.freeShipping).toBe(expected)
    expect(validatePurchasePaste([pasted],true,columns).records).toEqual(validatePurchasePaste([input],true,columns).records)
  })
  it.each([['','',''],['0','',''],['0','0',''],['','','0']])('does not turn incomplete freight %j into free shipping', (...freight) => {
    const input = row(freight)
    expect(inferredPasteFreeShipping(input,columns)).toBeUndefined()
    expect(validatePurchasePaste([input],true,columns).records[0]).not.toHaveProperty('freeShipping')
    expect(validatePurchasePaste([row(freight,'否')],true,columns).records[0]?.freeShipping).toBe('否')
  })
  it.each(['-1','abc','Infinity','0x0'])('rejects invalid freight %s rather than converting it to zero', invalid => {
    const input=row([invalid,'0','0'])
    expect(inferredPasteFreeShipping(input,columns)).toBeUndefined()
    expect(validatePurchasePaste([input],true,columns).canSave).toBe(false)
  })
  it('uses retained nonblank cells during a partial paste and leaves other rows untouched', () => {
    const existing=row(['3','0','0'],'否')
    const other=row(['6','11','56'],'是')
    const i=columns.findIndex(c=>c[1]==='singleFreightCny')
    const next=applyPurchasePaste([existing,other],[['0']],0,i,columns)
    expect(next[0]![columns.findIndex(c=>c[1]==='freeShipping')]).toBe('是')
    expect(next[1]).toEqual(other)
    expect(existing[i]).toBe('3')
  })
})
