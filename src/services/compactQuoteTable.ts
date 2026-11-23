import { customerGradeDisplayLabel } from '@/data/financeChannelPolicies'
import type { QuoteCopyMetadata } from '@/data/quoteCopyMetadata'
import { normalizeQuoteSheetOrder, quoteSheetCell, quoteSheetProviderKey, quoteSheetRowKey, type CustomerQuoteSheet, type QuoteSheetSourceRow } from '@/data/customerQuoteSheet'
import type { QuoteLocalPhoto } from './quoteLocalPhotos'
import { withQuoteSheetCopyLock } from './customerQuoteSheetCopyLock'

const escape = (value: string) => value.replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]!)).replace(/\r?\n/g, '<br>')
const plain = (value: string) => {
  const text = value.replace(/[\t\r\n]+/g, ' ')
  return /^[=+@-]/.test(text.trimStart()) ? "'" + text : text
}
export type CompactPhoto = { dataUrl: string; width: number; height: number }
// Chinese display names for this internal copy format only. Match whole names so
// custom channel descriptions and weighted-average labels remain intact.
const chineseProviders = new Map(Object.entries({
  Yanwen: '燕文', '4PX': '递四方', YunExpress: '云途', 'SFYD Express': '云速递',
  'SF Express': '顺丰', 'Wanb Express': '万邦', 'SDH Express': '闪电猴',
  SunYou: '顺友', TopYou: '通邮', JYTD: '捷易通达', JITO: '极通环球',
  'Hua Hai': '花海', Rongding: '容鼎', Qiaojie: '巧捷', 'Jisu International': '急速国际',
  Baizhou: '百洲', 'China Post': '中国邮政', Cainiao: '菜鸟',
  FedEx: '联邦快递', DHL: '敦豪', UPS: '联合包裹',
}).map(([name, chinese]) => [quoteSheetProviderKey(name), chinese]))

const chineseProvider = (name: string) => chineseProviders.get(quoteSheetProviderKey(name)) ?? name

function channelLabel(provider: string, source?: QuoteSheetSourceRow) {
  if (!source) return chineseProvider(provider)
  const carrier = chineseProvider(source.carrier?.trim() || provider)
  const channel = (source.transport || source.rule || '').trim()
  const region = source.quoteRegion === '全国统一' ? '' : source.quoteRegion?.trim() || ''
  return [...new Set([carrier, channel, region].filter(Boolean))].join(' · ')
}
/** Embed decoded, locally owned photos: no expiring URLs or asset uploads. */
export function compactTablePhotos(photos: readonly QuoteLocalPhoto[]): CompactPhoto[] {
  return photos.map(photo => {
    const width = photo.image.naturalWidth, height = photo.image.naturalHeight
    if (!width || !height) throw new Error('商品图片尚未读取完成，请重试')
    const scale = Math.min(1, 640 / Math.max(width, height))
    const canvas = document.createElement('canvas')
    try {
      canvas.width = Math.max(1, Math.round(width * scale)); canvas.height = Math.max(1, Math.round(height * scale))
      const context = canvas.getContext('2d')
      if (!context) throw new Error('当前浏览器无法读取商品图片')
      context.drawImage(photo.image, 0, 0, canvas.width, canvas.height)
      const displayScale = Math.min(1, 140 / Math.max(width, height))
      return { dataUrl: canvas.toDataURL('image/png'), width: Math.max(1, Math.round(width * displayScale)), height: Math.max(1, Math.round(height * displayScale)) }
    } finally { canvas.width = 0; canvas.height = 0 }
  })
}

/** Presentation only: use exact current/saved rows, never calculate a quote or modify snapshots. */
export function compactQuoteTable(sheet: CustomerQuoteSheet, photos: readonly CompactPhoto[] = [], metadata: QuoteCopyMetadata = {}, sources: readonly QuoteSheetSourceRow[] = []) {
  if (sheet.issues.length) throw new Error(sheet.issues.join('；'))
  if (!sheet.rows.length) throw new Error('请先选择报价渠道')
  if (sheet.fobBatch || sheet.showQuantityRange) throw new Error('此图文表格适用于按数量报价的物流报价单')
  if (sheet.rows.some(row => row.prices.length !== sheet.quantityLabels.length || row.prices.some(value => value !== null && (!Number.isFinite(value) || value < 0)))) throw new Error('报价价格或数量列无效，请检查后重新复制')
  if (photos.some(photo => !/^data:image\/png;base64,[A-Za-z0-9+/=]+$/.test(photo.dataUrl))) throw new Error('商品图片格式无效')
  const catalog = {
    sku: { label: 'SKU', width: 180 }, product: { label: 'Product', width: 180 },
    sizeRules: { label: 'Note', width: 180 }, country: { label: 'Country / Zone', width: 190 },
    prices: { label: 'Quote by Quantity (USD)', width: 115 }, provider: { label: 'Logistics Provider', width: 260 },
  }
  type Group = keyof typeof catalog
  const groups = normalizeQuoteSheetOrder(sheet.columnOrder ?? ['sku','product','sizeRules','country','prices','provider'])
    .filter((key): key is Group => Object.prototype.hasOwnProperty.call(catalog, key))
  const metadataHeaders = ['报价人', '报价日期', '报价客户', '客户等级', '客户佣金']
  const metadataValues = [metadata.salesperson ?? sheet.agent, metadata.date ?? sheet.date, metadata.customerName ?? sheet.customerName ?? '', customerGradeDisplayLabel(metadata.customerGrade ?? ''), typeof metadata.commissionThreshold === 'number' && Number.isFinite(metadata.commissionThreshold) ? String(metadata.commissionThreshold) : '']
  const metadataWidth = 120
  const widths = [...metadataHeaders.map(() => metadataWidth), ...groups.flatMap(key => key === 'prices' ? sheet.quantityLabels.map(() => catalog.prices.width) : [catalog[key].width])]
  const textRows = [[...metadataHeaders, ...groups.flatMap(key => key === 'prices' ? sheet.quantityLabels.map(label => `${label} (USD)`) : [catalog[key].label])]]
  const style='border:1px solid #d7d7d7;vertical-align:middle;text-align:center;font:14px Arial;white-space:normal;'
  const textStyle=style+'mso-number-format:"\\@";'
  const cell=(value:string,width=190)=>`<td width="${width}" style='${textStyle}width:${width*.75}pt'>${escape(value)}</td>`
  const header=(label:string,attrs='',width=180)=>`<th ${attrs} width="${width}" style='${style}width:${width*.75}pt;background:#f98019;color:white;font-weight:bold;height:30px'>${escape(label)}</th>`
  const body:string[]=[]
  // Resolve by stable row identity, never by display order or the current logistics library.
  const sourceByKey = new Map(sources.map(source => [quoteSheetRowKey(source), source]))
  for(let i=0;i<sheet.rows.length;){
    const first=sheet.rows[i]!
    let end=i+1
    while(end<sheet.rows.length && sheet.rows[end]!.sku===first.sku)end++
    const span=end-i
    const photoHeight=photos.reduce((sum,photo)=>sum+photo.height+12,0)
    const rowHeight=Math.max(36,Math.ceil(photoHeight/span))
    for(let j=i;j<end;j++){
      const row=sheet.rows[j]!, isFirst=j===i, bg=j%2?'#fff2e9':'#ffffff'
      const prices=row.prices.map(value=>value==null?'—':value.toFixed(2))
      const value = (key: Exclude<Group, 'prices'>) => key === 'sku' ? (isFirst ? row.sku ?? '' : '') : key === 'product' ? '' : key === 'sizeRules' ? (isFirst ? sheet.sizeRules ?? '' : '') : key === 'provider' ? channelLabel(row.provider, row.averageId ? undefined : sourceByKey.get(row.key)) : quoteSheetCell(row, key)
      textRows.push([...metadataValues.map(plain), ...groups.flatMap(key => key === 'prices' ? prices : [plain(value(key))])])
      const cells = groups.map(key => {
        if (key === 'prices') return prices.map((price,k)=>row.prices[k]==null?cell(price,115):`<td width="115" x:num="${price}" style='${style}width:86.25pt;mso-number-format:"0.00";font-weight:bold'>${price}</td>`).join('')
        if (key === 'country' || key === 'provider') return cell(value(key), catalog[key].width)
        if (!isFirst) return ''
        const content = key === 'product' ? photos.map(photo=>`<img src="${photo.dataUrl}" width="${photo.width}" height="${photo.height}" alt="商品图片"><br>`).join('') : escape(value(key))
        return `<td rowspan="${span}" width="180" style='${textStyle}width:135pt;background:white'>${content}</td>`
      }).join('')
      body.push(`<tr height="${rowHeight}" style="height:${rowHeight}px;background:${bg}">${metadataValues.map(value => `<td data-copy-metadata="value" width="${metadataWidth}" style='${textStyle}width:${metadataWidth*.75}pt'>${escape(value)}</td>`).join('')}${cells}</tr>`)
    }
    i=end
  }
  const groupHeaders = groups.map(key => header(catalog[key].label, key === 'prices' ? `colspan="${sheet.quantityLabels.length}"` : 'rowspan="2"', catalog[key].width * (key === 'prices' ? sheet.quantityLabels.length : 1))).join('')
  const metadataHeading = metadataHeaders.map(label => header(label, 'rowspan="2" data-copy-metadata="header"', metadataWidth)).join('')
  const table=`<table border="1" cellspacing="0" cellpadding="7" style="border-collapse:collapse;table-layout:fixed;width:${widths.reduce((a,b)=>a+b,0)}px"><colgroup>${widths.map(width=>`<col width="${width}" style="mso-width-source:userset;mso-width-alt:${Math.round(width*36.57)};width:${width*.75}pt">`).join('')}</colgroup><thead><tr>${metadataHeading}${groupHeaders}</tr><tr>${sheet.quantityLabels.map(label=>header(label,'',115)).join('')}</tr></thead><tbody>${body.join('')}</tbody></table>`
  return { html:`<html xmlns:x="urn:schemas-microsoft-com:office:excel"><head><meta charset="utf-8"></head><body><!--StartFragment-->${table}<!--EndFragment--></body></html>`, table, text:textRows.map(row=>row.join('\t')).join('\n') }
}

export async function copyCompactQuoteTable(table: ReturnType<typeof compactQuoteTable>, beforeWrite:()=>void) {
  if (!globalThis.isSecureContext || typeof ClipboardItem === 'undefined' || !navigator.clipboard?.write) throw new Error('当前浏览器不支持复制图文表格，请使用新版 Chrome 或 Edge')
  await withQuoteSheetCopyLock(async()=>{
    beforeWrite()
    try {
      await navigator.clipboard.write([new ClipboardItem({'text/html':new Blob([table.html],{type:'text/html'}),'text/plain':new Blob([table.text],{type:'text/plain'})})])
    } catch { throw new Error('图文表格未复制成功，请允许剪贴板权限后重试') }
  })
}
