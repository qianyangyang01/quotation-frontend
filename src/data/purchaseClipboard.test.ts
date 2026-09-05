// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { parsePurchaseHtmlTable, readPurchaseClipboard, readPurchaseClipboardResult } from './purchaseClipboard'
import { emptyPurchasePasteRow, parsePurchaseClipboard, validatePurchasePaste } from './purchasePaste'

export function multilinePurchaseFixture() {
  const first = emptyPurchasePasteRow(); const second = emptyPurchasePasteRow()
  Object.assign(first, {0:'2026.9.5',1:'采购员',3:'QA-PASTE-1',4:'850',5:'页数：118p\n内页：230*240mm\n尺寸：253*250*25mm',6:'小熊宝宝\n小兔宝宝\n鲸鱼宝宝\n小鸟宝宝',7:'双胶纸',11:'1',12:'41.1',13:'100',14:'40.12',15:'500',16:'38.16',17:'6',18:'30',19:'258',20:'否',21:'41.511',22:'1%',23:'普票1',24:'书',25:'有'})
  Object.assign(second, {0:'2026.9.5',1:'采购员',3:'QA-PASTE-2',4:'570',5:'产品尺寸\t47.5*20.5cm\n产品包装\t牛皮盒26*4.5*43.5cm',6:'伸缩蚊拍\n数显版',7:'ABS',11:'2',12:'27.5',17:'2.8',18:'20.8',19:'200.8',20:'否',21:'27.775',22:'1%',23:'普票1',24:'家用电器',25:'有'})
  return [first, second]
}
const htmlFor = (rows: string[][]) => `<html><body><!--StartFragment--><table><tbody>${rows.map(row=>`<tr>${row.map(cell=>`<td>${cell.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/\n/g,'<br>')}</td>`).join('')}</tr>`).join('')}</tbody></table><!--EndFragment--></body></html>`
const clipboard = (text: string, html = '') => ({ getData: (type: string) => type === 'text/html' ? html : text })

describe('spreadsheet clipboard boundaries', () => {
  it('filters three blank/image columns from whole rows while retaining multiline fields and prices', () => {
    const rows = multilinePurchaseFixture()
    const html = htmlFor(rows.map(row => ['', '', '', ...row])).replace('<td></td>', '<td><img src="https://invalid.example/a.png"><svg><text>图片</text></svg></td>')
    const result = readPurchaseClipboardResult(clipboard('', html), 0)
    expect(result).toEqual({ rows, skippedImageColumns: true })
    expect(validatePurchasePaste(result.rows).issues).toEqual([])
    const quoted = rows.map(row => ['', '[图片]', '=DISPIMG("id",1)', ...row].map(value => `"${value.replace(/"/g, '""')}"`).join('\t')).join('\r\n')
    expect(readPurchaseClipboard(clipboard(quoted), 0)).toEqual(rows)
  })
  it('accepts unused trailing Excel columns and partial source ranges with explicit data anchors', () => {
    const rows = multilinePurchaseFixture()
    const html = htmlFor(rows.map(row => ['', '', '', ...row])).replace(/<\/tr>/g, '<td colspan="16349"></td></tr>')
    expect(readPurchaseClipboard(clipboard('', html), 0)).toEqual(rows)
    const simple = rows.map(row => ['', '', '', ...row.map(value => value.replace(/[\t\n]/g, ' ')), ...Array(200).fill('')])
    expect(readPurchaseClipboard(clipboard(simple.map(r => r.join('\t')).join('\r\n')), 0)).toEqual(rows.map(row => row.map(value => value.replace(/[\t\n]/g, ' '))))
    expect(readPurchaseClipboard(clipboard('', htmlFor(rows.map(row => ['', '', '', ...row.slice(0, 26)]))), 0)).toEqual(rows.map(row => row.slice(0, 26)))
  })
  it('never removes three optional business columns or silently discards other source columns', () => {
    const rows = multilinePurchaseFixture().map(row => ['', '', '', ...row.slice(3)])
    expect(readPurchaseClipboardResult(clipboard('', htmlFor(rows)), 0)).toEqual({ rows, skippedImageColumns: false })
    expect(()=>readPurchaseClipboard(clipboard('', htmlFor(rows.map(row => [...row, '', '', '']))), 0)).toThrow('列数')
    expect(()=>readPurchaseClipboard(clipboard('', htmlFor(multilinePurchaseFixture().map(row => ['', '', '', ...row]))), 1)).toThrow('报价日期')
    expect(()=>readPurchaseClipboard(clipboard('', htmlFor(multilinePurchaseFixture().map(row => ['', '', '', ...row, '意外数据']))), 0)).toThrow('非空列')
  })
  it('reads two actual table rows despite unquoted newlines and tabs in plain text', () => {
    const rows = multilinePurchaseFixture(); const plain = rows.map(r=>r.join('\t')).join('\n')
    expect(parsePurchaseClipboard(plain).length).toBeGreaterThan(2)
    const result = readPurchaseClipboard(clipboard(plain, htmlFor(rows)), 0)
    expect(result).toEqual(rows)
    expect(result.map(r=>r.length)).toEqual([32,32])
    expect(validatePurchasePaste(result).issues).toEqual([])
  })
  it('keeps unquoted WPS LF inside a cell when CRLF separates records', () => {
    const rows = [['A','第一行\n第二行','','0'], ['B','正文\n说明','','20']]
    expect(readPurchaseClipboard(clipboard(rows.map(r=>r.join('\t')).join('\r\n')+'\r\n'),0)).toEqual(rows)
  })
  it('keeps quoted CRLF cell contents with LF-separated records', () => {
    expect(readPurchaseClipboard(clipboard('A\t"第一行\r\n第二行"\nB\t正文'),0)).toEqual([['A','第一行\n第二行'],['B','正文']])
  })
  it('rejects ambiguous unquoted LF text before modifying the grid', () => {
    const plain = multilinePurchaseFixture().map(r=>r.join('\t')).join('\n')
    expect(()=>readPurchaseClipboard(clipboard(plain),0)).toThrow('行列边界')
  })
  it('retains blank columns, nested text and literal special characters without executing markup', () => {
    const html='<table><tr><td><span>A&amp;B</span><br><b>下一行</b></td><td></td><td>0</td><td><script>window.bad=1</script><img src="https://invalid.example/image" onerror="window.bad=1">文字</td></tr></table>'
    expect(parsePurchaseHtmlTable(html)).toEqual([['A&B\n下一行','','0','文字']])
    expect(document.querySelector('img')).toBeNull()
  })
  it('rejects merged, nested, multiple tables and oversized rows', () => {
    for (const html of ['<table><tr><td colspan="2">合并</td></tr></table>', '<table><tr><td><table></table></td></tr></table>', '<table></table><table></table>', `<table>${'<tr><td>a</td></tr>'.repeat(101)}</table>`]) expect(()=>parsePurchaseHtmlTable(html)).toThrow()
    expect(parsePurchaseHtmlTable('<b>普通复制内容</b>')).toBeNull()
  })
  it('does not label missing SKUs as duplicates', () => {
    const rows = [emptyPurchasePasteRow(),emptyPurchasePasteRow()]; rows[0]![4]='50';rows[1]![4]='80'
    const issues=validatePurchasePaste(rows).issues
    expect(issues.filter(i=>i.column===3)).toHaveLength(2)
    expect(issues.some(i=>i.message.includes('重复'))).toBe(false)
  })
})
