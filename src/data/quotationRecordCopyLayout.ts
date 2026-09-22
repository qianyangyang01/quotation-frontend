import { customerGradeDisplayLabel } from './financeChannelPolicies'
import { quotationRecordReconciliationTsv } from './quotationRecordReconciliation'
import type { QuotationRecord } from './quotationRecords'

type LayoutRow = { kind: 'title' | 'section' | 'header' | 'data' | 'note' | 'blank'; cells: string[] }
const escapeHtml = (text: string) => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')
const spans = (length: number) => length === 4 ? [1, 2, 1, 2] : length === 2 ? [1, 5] : Array.from({ length }, (_, i) => i === 0 ? 7 - length : 1)

/** Reflow the existing reconciliation export; all amounts still come from its saved-data path. */
export function quotationRecordCopyLayout(record: QuotationRecord): { text: string; html: string } {
  const source = quotationRecordReconciliationTsv(record).split('\n').map(row => row.split('\t'))
  const tableStart = source.findIndex(row => row[0] === '序号')
  const headers = source[tableStart]!
  const quantityColumns = headers.flatMap((header, index) => header.endsWith('最终含包材重量（g）') ? [{ index, label: header.replace(/最终含包材重量（g）$/, '') }] : [])
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
  section('二、各国家与渠道报价')
  if (!routeCount) add('note', ['未保存国家与渠道明细'])
  for (const [index, route] of source.slice(tableStart + 1, tableStart + 1 + routeCount).entries()) {
    const value = (header: string) => route[headers.indexOf(header)] ?? '未保存'
    section(`${index + 1}. ${value('国家')} · ${value('物流商')} · ${value('渠道')}${value('首选') === '是' ? '（首选）' : ''}`)
    // Identity and rule fields appear once per route, instead of on every quantity row.
    const details: string[] = []
    for (let col = 4; col < quantityColumns[0]!.index; col++) {
      if (['物流商', '渠道'].includes(headers[col]!)) continue
      details.push(headers[col]!, route[col]!)
    }
    pairs(details.slice(0, 12))
    const groups: { label: string; values: string[] }[] = []
    for (const { label } of quantityColumns) {
      groups.push({ label, values: ['最终含包材重量（g）', '物流运费（CNY/单）', '系统价（USD）', '系统价（CNY）', '客户价（USD）', '客户价（CNY）', '关税（USD）', '操作费（USD/单）'].map(field => value(label + field)) })
    }
    groups.sort((a, b) => (parseInt(a.label) || Infinity) - (parseInt(b.label) || Infinity))
    add('header', ['数量', '系统报价 USD', '系统报价 CNY', '客户报价 USD', '客户报价 CNY'])
    for (const { label, values } of groups) add('data', [label, ...values.slice(2, 6)])
    add('blank', [''])
    add('header', ['数量', '含包材重量 g', '物流运费 CNY', '关税 USD', '附加费 USD', '操作费 USD'])
    for (const { label, values } of groups) add('data', [label, values[0]!, values[1]!, values[6]!, value('附加费（USD/单）'), values[7]!])
    add('blank', [''])
    add('header', ['计费补充信息'])
    pairs(details.slice(12))
  }
  section('三、包材、商品及成交明细')
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
  section('四、采购、审核与成交信息')
  source.slice(5, tableStart).filter(row => row.length > 1).forEach(pairs)
  const text = rows.map(row => row.cells.join('\t')).join('\n')
  const htmlRows = rows.map(row => {
    const widths = spans(row.cells.length)
    const background = row.kind === 'title' ? '#18334d' : row.kind === 'section' ? '#e8eef5' : row.kind === 'header' ? '#f1f5f9' : '#ffffff'
    const bold = ['title', 'section', 'header'].includes(row.kind)
    return `<tr>${row.cells.map((value, i) => `<td colspan="${widths[i]}" style="padding:${row.kind === 'blank' ? '5px' : '9px 12px'};border:${row.kind === 'blank' ? '0' : '1px solid #dbe3ec'};background:${background};color:${row.kind === 'title' ? '#ffffff' : row.kind === 'note' ? '#66788a' : '#18334d'};font-weight:${bold ? '700' : '400'};font-size:${row.kind === 'title' ? '20px' : '12px'};text-align:${row.kind === 'data' && /^\d+(\.\d+)?$/.test(value) ? 'right' : 'left'};vertical-align:top;white-space:normal;word-wrap:break-word;mso-number-format:'\\@'">${escapeHtml(value)}</td>`).join('')}</tr>`
  }).join('')
  return { text, html: `<html><head><meta charset="utf-8"></head><body><!--StartFragment--><table style="border-collapse:collapse;table-layout:fixed;width:900px;font-family:Arial,'Microsoft YaHei',sans-serif"><colgroup>${'<col width="150" style="width:150px">'.repeat(6)}</colgroup>${htmlRows}</table><!--EndFragment--></body></html>` }
}
