// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { readFobClipboard } from './fobClipboard'
const data = (plain: string, html = '') => ({ getData: (format: string) => format === 'text/html' ? html : plain })
function original(sku: string) {
  const row = Array<string>(30).fill('')
  Object.assign(row, { 2: sku, 7: '约80g', 8: '80', 12: '2', 13: '单价16.5\n100起单价12.8', 15: '一件包邮;100件30', 16: '0', 17: '16.5', 18: '文胸', 19: '16.665', 20: '1%' })
  return row
}
const tsv = (rows: string[][]) => rows.map(row => row.map(c => /[\t\r\n"]/.test(c) ? `"${c.replace(/"/g,'""')}"` : c).join('\t')).join('\r\n')
describe('FOB original spreadsheet clipboard', () => {
  it('reads multiple full rows and preserves multiline price cells, using N/P not Q/R/T', () => {
    const rows = readFobClipboard(data(tsv([original('PF2600040'), original('PF2600042')])))
    expect(rows).toHaveLength(2)
    expect(rows[0]).toMatchObject({ sku: 'PF2600040', weightRaw: '80', moqRaw: '2', priceRaw: '单价16.5\n100起单价12.8', freightRaw: '一件包邮;100件30' })
    expect(rows[0]).not.toHaveProperty('taxIncludedPriceCny')
  })
  it('accepts SKU-to-freight ranges without shifting blank internal fields', () => {
    const row = original('PF2600053').slice(2,16)
    expect(readFobClipboard(data(tsv([row])))[0]).toMatchObject({ sku: 'PF2600053', priceRaw: row[11], freightRaw: row[13] })
  })
  it('maps partial headers and treats zero as present and blank as absent', () => {
    expect(readFobClipboard(data('SKU\t运费原文\t采购价格原文\r\nPF2600040\t0\t'))).toEqual([{ sku: 'PF2600040', freightRaw: '0' }])
  })
  it('prefers HTML cell boundaries and ignores copied images', () => {
    const html = '<table><tr><td>SKU</td><td>单价</td><td>运费/试拍或议价</td></tr><tr><td>PF2600053</td><td>单价16<br>100单价13.8<img src="invalid"></td><td>包邮</td></tr></table>'
    expect(readFobClipboard(data('broken plain text',html))[0]!.priceRaw).toBe('单价16\n100单价13.8')
  })
  it('accepts 100 rows with header and rejects 101 products or unframed data', () => {
    const html = '<table><tr><td>SKU</td><td>单价</td></tr>' + Array.from({length:100},(_,i)=>`<tr><td>PF${i}</td><td>12</td></tr>`).join('') + '</table>'
    expect(readFobClipboard(data('',html))).toHaveLength(100)
    expect(() => readFobClipboard(data('SKU\t单价\r\n'+Array.from({length:101},(_,i)=>`PF${i}\t12`).join('\r\n')))).toThrow('100')
    expect(() => readFobClipboard(data('PF2600053\t16'))).toThrow('原表格式')
    expect(() => readFobClipboard(data('SKU\t单价\r\nPF2600053\t16\r\n100\t12\tbroken'))).toThrow('行列边界')
  })
})
