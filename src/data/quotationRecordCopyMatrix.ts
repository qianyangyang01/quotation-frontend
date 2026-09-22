import Decimal from 'decimal.js'
import { customerGradeDisplayLabel } from './financeChannelPolicies'
import type { QuotationRecord } from './quotationRecords'
import { quotationProductCostSnapshot, snapshotMoney } from './quotationProductCostSnapshot'

const escapeHtml = (value: string) => value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')
const safe = (value: string) => {
  const text = value.replace(/[\t\r\n]+/g, ' ')
  return /^[=+@-]/.test(text.trimStart()) ? `'${text}` : text
}
const grams = (value?: number) => value == null ? '未保存' : new Decimal(value).mul(1000).toFixed()

/** Presentation only: the reconciliation export remains the source of all saved prices. */
export function quotationRecordCopyMatrix(record: QuotationRecord, source: string[][]) {
  const start = source.findIndex(row => row[0] === '序号')
  const headers = source[start]!
  const routes = source.slice(start + 1, start + 1 + (record.quoteOptions?.length ?? 0))
  const unit = record.quoteMode === 'bundle' ? '套' : '件'
  const savedLabels = headers.filter(header => header.endsWith('客户价（USD）')).map(header => header.replace(/客户价（USD）$/, ''))
  const labels = [...new Set([...Array.from({ length: 8 }, (_, i) => `${i + 1}${unit}`), ...savedLabels])]
    .sort((a, b) => (parseInt(a) || Infinity) - (parseInt(b) || Infinity))
  const value = (route: string[], field: string, missing = '未保存') => route[headers.indexOf(field)] ?? missing
  const primaryIndex = Math.max(0, record.quoteOptions?.findIndex(option => option.isPrimary) ?? -1)
  const primary = record.quoteOptions?.[primaryIndex]
  const primaryRoute = routes[primaryIndex]
  const weight = record.weightSnapshot?.quantities.find(row => row.quantity === 1)
  const cost = quotationProductCostSnapshot(record)
  const metadata = [
    ['SKU', source[2]?.[1] ?? '未保存', '客户', source[1]?.[3] ?? '未保存', '客户等级', safe(customerGradeDisplayLabel(record.customerGrade))],
    [`计入采购价 CNY/${unit}`, snapshotMoney(cost.rows.find(row => row.quantity === 1)?.purchase), '运费 CNY（首选快照）', primaryRoute ? value(primaryRoute, '物流运费快照（CNY）') : '未保存', '合计成本 CNY（首选快照）', source[8]?.[5] ?? '未保存'],
    [`商品重量 g/1${unit}`, grams(weight?.baseWeightKg), `包材重量 g/1${unit}`, weight ? grams(new Decimal(weight.standardPackagingWeightKg).plus(weight.specialPackagingWeightKg).toNumber()) : '未保存', `合计重量 g/1${unit}`, grams(weight?.weightKg)],
    ['报价编号', source[1]?.[1] ?? '未保存', '创建时间', source[4]?.[5] ?? '未保存', '首选运费快照数量', primary?.logisticsInput?.quantity ? `${primary.logisticsInput.quantity}${unit}` : '未保存'],
  ]
  const detailHeaders = ['物流运费 CNY/单', '关税 USD/单', '附加费 USD/单', '操作费 USD/单', '含包材重量 g', '关税说明', '附加费说明', '系统报价 USD', '系统报价 CNY', '客户报价 CNY', '渠道及计费信息']
  const heading = ['国家', '运输', ...labels, ...detailHeaders]
  const columnCount = heading.length
  const textRows = [...metadata]
  const renderCell = (text: string, background = '#ffffff', bold = false, colspan = 1, align = 'center') => `<td colspan="${colspan}" style="border:1px solid #555555;padding:4px 6px;background:${background};color:#000000;font-family:Arial,'Microsoft YaHei',sans-serif;font-size:${bold ? '18' : '12'}px;font-weight:${bold ? '700' : '400'};text-align:${align};vertical-align:middle;white-space:normal;word-wrap:break-word;mso-number-format:'\\@'">${escapeHtml(text)}</td>`
  const htmlRows = metadata.map(row => `<tr>${row.map((cell, i) => renderCell(cell, i === 1 && row[0] === 'SKU' ? '#e2f4f4' : '#ffffff', false, i === 5 ? columnCount - 5 : 1, 'left')).join('')}</tr>`)
  const countryLabel = (route: string[]) => {
    const country = value(route, '国家'), region = value(route, '区域')
    return region && !['全国统一', '未保存', country].includes(region) ? `${country} · ${region}` : country
  }
  const countryColor = (country: string) => /香港/.test(country) ? '#00b0f0' : /加拿大|澳大利亚/.test(country) ? '#ff0000' : /美国/.test(country) ? '#ffc000' : /英国/.test(country) ? '#ffff00' : '#92d050'
  textRows.push([''], ['各数量客户报价 USD/单及费用明细'], heading)
  htmlRows.push(`<tr>${renderCell('各数量客户报价 USD/单及费用明细', '#ffffff', false, columnCount, 'left')}</tr>`)
  htmlRows.push(`<tr>${heading.map(cell => renderCell(cell, '#c6e0b4', true)).join('')}</tr>`)
  const firstQuantityColumn = headers.findIndex(header => header.endsWith('最终含包材重量（g）'))
  const extraHeaders = headers.slice(4, firstQuantityColumn).filter(header => !['物流商', '渠道', '关税说明', '附加费说明', '附加费（USD/单）'].includes(header))
  for (const route of routes) {
    // One spreadsheet row per route. Quantity labels travel with each fee so
    // totals for different order sizes cannot be mistaken for a flat per-order fee.
    const summary = (field: string, missing = '未保存') => labels.map(label => `${label}：${value(route, label + field, missing)}`).join('；')
    const cells = [countryLabel(route), `${value(route, '物流商')}｜${value(route, '渠道')}`,
      ...labels.map(label => value(route, label + '客户价（USD）', '未报价')),
      summary('物流运费（CNY/单）'), summary('关税（USD）'), value(route, '附加费（USD/单）'), summary('操作费（USD/单）'),
      summary('最终含包材重量（g）'), value(route, '关税说明'), value(route, '附加费说明'),
      summary('系统价（USD）'), summary('系统价（CNY）'), summary('客户价（CNY）', '未报价'),
      extraHeaders.map(header => `${header}：${value(route, header)}`).join('；'),
    ]
    textRows.push(cells)
    htmlRows.push(`<tr data-quotation-route="true">${cells.map((cell, i) => renderCell(cell, i === 0 ? countryColor(value(route, '国家')) : i === 1 ? (/香港/.test(value(route, '国家')) ? '#00b0f0' : '#92d050') : '#ffffff', false, 1, i >= labels.length + 2 ? 'left' : 'center')).join('')}</tr>`)
  }
  if (!routes.length) {
    textRows.push(['未保存国家与渠道明细'])
    htmlRows.push(`<tr>${renderCell('未保存国家与渠道明细', '#ffffff', false, columnCount)}</tr>`)
  }
  const note = '每行对应一个国家和物流渠道，报价及费用均按整单展示，币种见列名；费用格内按数量标注。未保存项不补算。'
  textRows.push([note], [''])
  htmlRows.push(`<tr>${renderCell(note, '#ffffff', false, columnCount, 'left')}</tr>`)
  const widths = [170, 300, ...labels.map(() => 100), ...detailHeaders.map(header => header === '渠道及计费信息' ? 440 : 220)]
  return {
    text: textRows.map(row => row.join('\t')).join('\n'),
    html: `<table aria-label="报价与运费横向表" style="border-collapse:collapse;table-layout:fixed;width:${widths.reduce((a, b) => a + b, 0)}px"><colgroup>${widths.map(width => `<col width="${width}" style="width:${width}px">`).join('')}</colgroup>${htmlRows.join('')}</table>`,
  }
}
