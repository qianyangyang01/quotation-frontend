import { customerGradeDisplayLabel } from './financeChannelPolicies'
import { quotationRecordReconciliationTsv } from './quotationRecordReconciliation'
import { quotationRecordCopyMatrix } from './quotationRecordCopyMatrix'
import { quotationProductCostSnapshot, snapshotMoney } from './quotationProductCostSnapshot'
import type { QuotationRecord } from './quotationRecords'

type LayoutRow = { kind: 'title' | 'section' | 'header' | 'data' | 'note' | 'blank'; cells: string[] }
const escapeHtml = (text: string) => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')
const spans = (length: number) => length === 4 ? [1, 2, 1, 2] : length === 2 ? [1, 5] : Array.from({ length }, (_, i) => i === 0 ? 7 - length : 1)

/** Reflow the existing reconciliation export; all amounts still come from its saved-data path. */
export function quotationRecordCopyLayout(record: QuotationRecord): { text: string; html: string } {
  const source = quotationRecordReconciliationTsv(record).split('\n').map(row => row.split('\t'))
  const matrix = quotationRecordCopyMatrix(record, source)
  const tableStart = source.findIndex(row => row[0] === '序号')
  const routeCount = record.quoteOptions?.length ?? 0
  const rows: LayoutRow[] = []
  const add = (kind: LayoutRow['kind'], cells: string[]) => rows.push({ kind, cells })
  const section = (title: string) => { add('blank', ['']); add('section', [title]) }
  const pairs = (cells: string[]) => {
    for (let i = 0; i < cells.length; i += 4) add('data', cells.slice(i, i + 4))
  }
  add('title', ['报价记录 · 对账明细'])
  add('note', ['报价及费用表按整单展示，币种见列名；人民币按本记录汇率换算，未保存项不补算。'])
  section('一、报价基本信息')
  for (const cells of source.slice(1, 5)) {
    const display = [...cells]
    const grade = display.indexOf('客户等级')
    if (grade >= 0) display[grade + 1] = customerGradeDisplayLabel(display[grade + 1]!)
    pairs(display)
  }
  section('产品成本快照（CNY）')
  const cost = quotationProductCostSnapshot(record)
  const safeCell = (value: string) => {
    const text = value.replace(/[\t\r\n]+/g, ' ')
    return /^[=+@-]/.test(text.trimStart()) ? `'${text}` : text
  }
  for (const item of cost.items) {
    pairs(['SKU', safeCell(item.sku), cost.bundle ? '每套件数' : '件数', String(item.count), '采购原价/件', snapshotMoney(item.base), '计入采购价/件', snapshotMoney(item.purchase), '采购发票', safeCell(item.invoice || '未保存'), '票点', item.rate == null ? '未保存' : `${item.rate}%`, '国内运费/件', snapshotMoney(item.freight)])
  }
  add('header', ['数量', '采购成本 CNY', '国内运费 CNY', '产品成本合计 CNY'])
  for (const row of cost.rows) add('data', [`${row.quantity}${cost.unit}`, snapshotMoney(row.purchase), snapshotMoney(row.freight), snapshotMoney(row.total)])
  if (cost.freightRecovered) add('note', ['国内运费由本报价已保存的同数量成本与国际运费还原。'])
  add('note', ['产品成本合计为采购成本＋国内运费，不含国际运费、关税或操作费；缺失项不回填。'])
  section('二、包材、商品及成交明细')
  // Preserve every appendix field. Wide appendix tables are split into narrow
  // tables with their identifier repeated, so the pasted sheet stays six columns wide.
  const appendix = source.slice(tableStart + 1 + routeCount)
  for (let i = 0; i < appendix.length;) {
    const row = appendix[i]!
    if (row.length === 1) {
      if (row[0]) add(row[0].includes('未保存') ? 'note' : 'section', row)
      i++; continue
    }
    if (row[0] === '普通包材规则') { pairs(row); i++; continue }
    const heading = row
    const data: string[][] = []
    i++
    while (i < appendix.length && appendix[i]!.length === heading.length) data.push(appendix[i++]!)
    const columns = heading.length <= 6 ? [heading.map((_, col) => col)] : [Array.from({ length: 6 }, (_, col) => col), [0, ...Array.from({ length: heading.length - 6 }, (_, col) => col + 6)]]
    for (const chunk of columns) {
      add('header', chunk.map(col => heading[col]!))
      data.forEach(values => add('data', chunk.map(col => values[col]!)))
      add('blank', [''])
    }
  }
  section('三、采购、审核与成交信息')
  source.slice(5, tableStart).filter(row => row.length > 1).forEach(pairs)
  const text = matrix.text + '\n' + rows.map(row => row.cells.join('\t')).join('\n')
  const htmlRows = rows.map(row => {
    const widths = spans(row.cells.length)
    const background = row.kind === 'title' || row.kind === 'section' || row.kind === 'header' ? '#c6e0b4' : '#ffffff'
    const bold = ['title', 'section', 'header'].includes(row.kind)
    return `<tr>${row.cells.map((value, i) => `<td colspan="${widths[i]}" style="padding:${row.kind === 'blank' ? '5px' : '6px 8px'};border:${row.kind === 'blank' ? '0' : '1px solid #555555'};background:${background};color:#000000;font-weight:${bold ? '700' : '400'};font-size:${row.kind === 'title' ? '20px' : '12px'};text-align:${row.kind === 'data' && /^\d+(\.\d+)?$/.test(value) ? 'right' : 'left'};vertical-align:top;white-space:normal;word-wrap:break-word;mso-number-format:'\\@'">${escapeHtml(value)}</td>`).join('')}</tr>`
  }).join('')
  return { text, html: `<html><head><meta charset="utf-8"></head><body><!--StartFragment-->${matrix.html}<br><table aria-label="保存的对账明细" style="border-collapse:collapse;table-layout:fixed;width:900px;font-family:Arial,'Microsoft YaHei',sans-serif"><colgroup>${'<col width="150" style="width:150px">'.repeat(6)}</colgroup>${htmlRows}</table><!--EndFragment--></body></html>` }
}
