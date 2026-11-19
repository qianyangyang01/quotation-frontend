import JSZip from 'jszip'
import { request } from './http'
import { publicationTime, type PublishedPriceVersion } from '@/data/logisticsRealPriceHistory'
import { exportFee, exportInterval, exportRate, exportRules, type BatchExportData, type BatchExportRow, type ExportPrice } from '@/data/logisticsBatchExport'

export async function loadExportVersionRows(version: PublishedPriceVersion, signal?: AbortSignal): Promise<ExportPrice[]> {
  const data = await request<{ id: string; channelId: string; status: string; rows?: ExportPrice[]; rowCount?: number }>(`/logistics/rebuild/versions/${encodeURIComponent(version.id)}`, { signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(60000)]) : AbortSignal.timeout(60000) })
  if (data.id !== version.id || data.channelId !== version.channel || !['published', 'superseded'].includes(data.status) || !Array.isArray(data.rows)
    || (data.rowCount != null && data.rowCount !== data.rows.length)) throw new Error('版本资料不完整，请刷新后重试')
  return data.rows
}
// XML 1.0 disallows these control characters in text cells.
// eslint-disable-next-line no-control-regex
const esc = (value: unknown) => String(value ?? '').replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
const col = (n: number): string => n < 26 ? String.fromCharCode(65 + n) : col(Math.floor(n / 26) - 1) + col(n % 26)
const NS = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
const REL = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
const XML = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
type Cell = string | number | null
const headers = ['国家', '渠道', '属性', '分区', '重量段（g）', '原单价\n元/kg', '新单价\n元/kg', '单价差额\n元/kg', '单价涨跌幅', '原固定费\n元/票', '新固定费\n元/票', '固定费差额\n元/票', '变动', '备注', '币种（原/新）', '上传批次', '发布版本', '原版本', '新价表文件', '原价表文件', '原计费规则', '新计费规则', '原区间价\n元/票', '新区间价\n元/票', '区间价差额\n元/票', '物流商', '起运地', '发布时间', '原表页 / 行', '新表页 / 行']
function rowValues(r: BatchExportRow): Cell[] {
  const currencies = [r.before?.currency || (r.before ? 'CNY' : '—'), r.after?.currency || (r.after ? 'CNY' : '—')]
  const compatible = currencies[0] === currencies[1]
  const pieceBefore = r.before?.pricingModel === 'per-piece-500g', pieceAfter = r.after?.pricingModel === 'per-piece-500g'
  const beforeInterval = pieceBefore ? exportInterval(r.before) : null, afterInterval = pieceAfter ? exportInterval(r.after) : null
  return [r.country, r.channel.name, r.channel.attribute, r.region, r.weight,
    pieceBefore ? null : exportRate(r.before), pieceAfter ? null : exportRate(r.after), compatible ? r.rateDelta : null, compatible ? r.ratePercent : null,
    exportFee(r.before), exportFee(r.after), compatible ? r.feeDelta : null, r.status, r.note, currencies.join(' / '), r.version.importBatchId || '独立发布',
    `V${r.version.number} / ${r.version.id}`, r.baseline ? `V${r.baseline.number} / ${r.baseline.id}` : '—', r.version.fileName, r.baseline?.fileName || '—',
    exportRules(r.before), exportRules(r.after), beforeInterval, afterInterval, compatible && beforeInterval !== null && afterInterval !== null ? afterInterval - beforeInterval : null,
    r.channel.provider, r.origin || '—', publicationTime(r.version.publishedAt), r.before ? `${r.before.sourceSheet || '—'} / ${r.before.sourceRow ?? '—'}` : '—', r.after ? `${r.after.sourceSheet || '—'} / ${r.after.sourceRow ?? '—'}` : '—']
}
const widths = [10, 22, 12, 18, 18, 12, 12, 12, 14, 14, 14, 14, 12, 36, 20, 40, 48, 48, 40, 40, 64, 64, 16, 16, 16, 24, 22, 24, 30, 30]
function rowHeight(row: Cell[], columnWidths: number[]) {
  const lines = Math.max(...row.map((value, n) => Math.ceil(Array.from(String(value ?? '')).reduce((size, c) => size + (c.charCodeAt(0) > 255 ? 2 : 1), 0) / Math.max(8, columnWidths[n]! - 2))))
  return Math.max(30, Math.min(150, lines * 15 + 8))
}
function cell(value: Cell, address: string, style: number) {
  if (value === null) return `<c r="${address}" s="${style}" t="inlineStr"><is><t>—</t></is></c>`
  if (typeof value === 'number' && Number.isFinite(value)) return `<c r="${address}" s="${style}"><v>${value}</v></c>`
  return `<c r="${address}" s="${style}" t="inlineStr"><is><t xml:space="preserve">${esc(value)}</t></is></c>`
}
// Every cell style uses horizontal and vertical centering. Text remains text, including leading '='.
function styles() {
  const fonts = ['<font><sz val="11"/><color rgb="FF333333"/><name val="Microsoft YaHei"/></font>', '<font><b/><sz val="11"/><color rgb="FF333333"/><name val="Microsoft YaHei"/></font>', '<font><b/><sz val="11"/><color rgb="FFCE423B"/><name val="Microsoft YaHei"/></font>', '<font><b/><sz val="11"/><color rgb="FF168878"/><name val="Microsoft YaHei"/></font>', '<font><sz val="11"/><color rgb="FFA47132"/><name val="Microsoft YaHei"/></font>']
  const xf = (font = 0, fill = 0, num = 0) => `<xf numFmtId="${num}" fontId="${font}" fillId="${fill}" borderId="0" xfId="0" applyAlignment="1" applyFont="1" applyFill="1" applyNumberFormat="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>`
  return `${XML}<styleSheet xmlns="${NS}"><numFmts count="2"><numFmt numFmtId="164" formatCode="+0.00;-0.00;0.00"/><numFmt numFmtId="165" formatCode="+0.0%;-0.0%;0.0%"/></numFmts><fonts count="5">${fonts.join('')}</fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE8EDF2"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="11">${[xf(), xf(), xf(0, 0, 2), xf(1, 2), xf(1), xf(2), xf(3), xf(4), xf(0, 0, 165), xf(2, 0, 164), xf(3, 0, 164)].join('')}</cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>`
}
export async function batchPriceWorkbook(data: BatchExportData): Promise<Blob> {
  const zip = new JSZip()
  const totals = { up: 0, down: 0, same: 0, other: 0 }
  const countries = new Map<string, typeof totals>()
  for (const r of data.rows) { totals[r.category]++; const v = countries.get(r.country) || { up: 0, down: 0, same: 0, other: 0 }; v[r.category]++; countries.set(r.country, v) }
  const foreignCurrency = data.rows.some(r => [r.before, r.after].some(p => p && p.currency && p.currency !== 'CNY'))
  const detailHeaders = foreignCurrency ? headers.map(h => h.replace('元/', '原币/')) : headers
  const sheets = [
    { name: '批次汇总', headers: ['国家', '涨价', '降价', '不变', '其他', '合计'], widths: [28, 18, 18, 18, 18, 18], rows: [...countries].map(([c, v]) => [c, v.up, v.down, v.same, v.other, v.up + v.down + v.same + v.other] as Cell[]) },
    ...(['up', 'down', 'same', 'other'] as const).map((category, i) => ({ name: ['涨价明细', '降价明细', '不变明细', '其他变动'][i]!, headers: detailHeaders, widths, rows: data.rows.filter(r => r.category === category).map(rowValues) })),
  ]
  for (const [i, sheet] of sheets.entries()) {
    if (sheet.rows.length > 1_048_560) throw new Error('该批次超出 Excel 行数限制，请按原始批次导出')
    const lastCol = col(sheet.headers.length - 1), values = sheet.rows.length ? sheet.rows : [sheet.headers.map((_, index) => index === 0 ? '无记录' : '')]
    const end = values.length + 6, ref = `A6:${lastCol}${end}`
    const info: string[] = [sheet.name + (data.sample ? '（样例数据）' : ''), `批次：${data.batch.id}    操作人：${data.batch.operator || '未记录'}`, `更新时间：${publicationTime(data.batch.windowStartAt || data.batch.startedAt)}${data.batch.windowEndAt ? '—' + publicationTime(data.batch.windowEndAt).slice(11) : ''}    全部国家、全部重量段`,
      i === 0 ? `涨价 ${totals.up} 条，降价 ${totals.down} 条，不变 ${totals.same} 条，其他 ${totals.other} 条；共 ${data.rows.length} 条重量段记录。` : '固定费为渠道单票费／挂号费。涨跌幅按单价计算。[ ]含边界，( )不含边界。']
    let rows = info.map((v, n) => `<row r="${n + 1}" ht="${n ? 26 : 30}" customHeight="1">${cell(v, `A${n + 1}`, n ? 1 : 4)}</row>`).join('')
    rows += `<row r="6" ht="38" customHeight="1">${sheet.headers.map((v, n) => cell(v, `${col(n)}6`, 3)).join('')}</row>`
    rows += values.map((row, index) => `<row r="${index + 7}" ht="${rowHeight(row, sheet.widths)}" customHeight="1">${row.map((v, n) => {
      let style = typeof v === 'number' && i ? 2 : 1
      if (i && n === 8) style = 8
      if (i && [7, 11, 24].includes(n) && typeof v === 'number') style = v > 0 ? 9 : v < 0 ? 10 : 2
      if (i && n === 12) style = i === 1 ? 5 : i === 2 ? 6 : i === 4 ? 7 : 1
      return cell(v, `${col(n)}${index + 7}`, style)
    }).join('')}</row>`).join('')
    zip.file(`xl/worksheets/sheet${i + 1}.xml`, `${XML}<worksheet xmlns="${NS}" xmlns:r="${REL}"><dimension ref="A1:${lastCol}${end}"/><sheetViews><sheetView workbookViewId="0" showGridLines="0"><pane xSplit="${i ? 2 : 1}" ySplit="6" topLeftCell="${i ? 'C' : 'B'}7" activePane="bottomRight" state="frozen"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight="24"/><cols>${sheet.widths.map((w, n) => `<col min="${n + 1}" max="${n + 1}" width="${w}" customWidth="1"/>`).join('')}</cols><sheetData>${rows}</sheetData><mergeCells count="4">${info.map((_, n) => `<mergeCell ref="A${n + 1}:${i ? 'N' : lastCol}${n + 1}"/>`).join('')}</mergeCells><tableParts count="1"><tablePart r:id="rId1"/></tableParts></worksheet>`)
    zip.file(`xl/tables/table${i + 1}.xml`, `${XML}<table xmlns="${NS}" id="${i + 1}" name="BatchPrices${i + 1}" displayName="BatchPrices${i + 1}" ref="${ref}" totalsRowShown="0"><autoFilter ref="${ref}"/><tableColumns count="${sheet.headers.length}">${sheet.headers.map((h, n) => `<tableColumn id="${n + 1}" name="${esc(h).replace(/\n/g, '&#10;')}"/>`).join('')}</tableColumns><tableStyleInfo name="TableStyleLight1" showFirstColumn="0" showLastColumn="0" showRowStripes="0" showColumnStripes="0"/></table>`)
    zip.file(`xl/worksheets/_rels/sheet${i + 1}.xml.rels`, `${XML}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="${REL}/table" Target="../tables/table${i + 1}.xml"/></Relationships>`)
  }
  zip.file('xl/styles.xml', styles())
  zip.file('xl/workbook.xml', `${XML}<workbook xmlns="${NS}" xmlns:r="${REL}"><bookViews><workbookView activeTab="0"/></bookViews><sheets>${sheets.map((s, i) => `<sheet name="${s.name}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join('')}</sheets></workbook>`)
  zip.file('xl/_rels/workbook.xml.rels', `${XML}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${sheets.map((_, i) => `<Relationship Id="rId${i + 1}" Type="${REL}/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`).join('')}<Relationship Id="rId6" Type="${REL}/styles" Target="styles.xml"/></Relationships>`)
  zip.file('_rels/.rels', `${XML}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="${REL}/officeDocument" Target="xl/workbook.xml"/></Relationships>`)
  zip.file('[Content_Types].xml', `${XML}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${sheets.map((_, i) => `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/tables/table${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml"/>`).join('')}</Types>`)
  return zip.generateAsync({ type: 'blob', compression: 'DEFLATE', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
}
export function saveBatchWorkbook(blob: Blob, data: BatchExportData) {
  const url = URL.createObjectURL(blob), a = document.createElement('a')
  a.href = url;a.download = `批次价格明细-${publicationTime(data.batch.windowStartAt || data.batch.startedAt).replace(/[: ]/g, '-')}-全部重量段.xlsx`
  document.body.appendChild(a);a.click();a.remove();setTimeout(() => URL.revokeObjectURL(url), 60000)
}
