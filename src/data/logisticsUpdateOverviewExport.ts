import { publicationTime } from './logisticsRealPriceHistory'
import { updateAmount, updateCounts, updatePercent, type PriceUpdateGroup, type PriceUpdateRow } from './logisticsUpdateOverview'

interface OverviewExport {
  batch: PriceUpdateGroup; rows: PriceUpdateRow[]; country: string; attribute: string; weight: number; filter: string; search: string
}
const pageSize = 25

async function renderPage(data: OverviewExport, rows: PriceUpdateRow[], page: number, pages: number): Promise<Blob> {
  await document.fonts.ready
  const canvas = document.createElement('canvas')
  canvas.width = 1600; canvas.height = 410 + rows.length * 106 + 100
  const context = canvas.getContext('2d')
  if (!context) throw new Error('无法生成概览图')
  context.fillStyle = '#fff'; context.fillRect(0, 0, canvas.width, canvas.height)
  function text(value: string, x: number, y: number, size = 22, color = '#294660', bold = false, maxWidth?: number) {
    context!.font = `${bold ? '700' : '400'} ${size}px "Microsoft YaHei",sans-serif`
    context!.fillStyle = color
    let rendered = value
    if (maxWidth && context!.measureText(rendered).width > maxWidth) {
      while (rendered.length && context!.measureText(rendered + '…').width > maxWidth) rendered = rendered.slice(0, -1)
      rendered += '…'
    }
    context!.fillText(rendered, x, y)
  }
  const counts = updateCounts(data.rows)
  text('价格更新概览', 40, 65, 38, '#233f57', true)
  text(`${data.country} · ${data.attribute} · ${data.weight} kg · 元 / 票`, 40, 111, 25)
  text(data.batch.windowStartAt ? `操作人：${data.batch.operator} · 固定4小时 · ${data.batch.imports.length} 个原始上传批次` : `独立记录：${data.batch.batches[0]?.importBatchId || data.batch.rows[0]?.after.id}`, 40, 151, 20)
  text(data.batch.windowStartAt ? `导入窗口：${publicationTime(data.batch.windowStartAt)} — ${publicationTime(data.batch.windowEndAt!)}` : `发布时间：${publicationTime(data.batch.startedAt)} — ${publicationTime(data.batch.publishedAt)}`, 40, 186, 20)
  text(`导出范围：${data.filter}${data.search ? ' · 搜索：' + data.search : ''} · 共 ${data.rows.length} 条 · 第 ${page} / ${pages} 页`, 40, 221, 20, '#617b94', false, 1500)
  text(`涨价 ${counts.up} 条     降价 ${counts.down} 条     不变 ${counts.same} 条     首次 / 新增 / 移除 / 不可比 ${counts.first + counts.added + counts.removed + counts.unavailable} 条`, 40, 275, 25, '#294660', true)
  context.fillStyle = '#f1f5fa'; context.fillRect(40, 310, 1520, 65)
  const xs = [58, 493, 748, 1000, 1400]
  ;['渠道名称 / 分区', '更新前价格', '更新后价格', '具体涨跌金额', '涨跌幅'].forEach((heading, index) => text(heading, xs[index]!, 351, 23))
  rows.forEach((row, index) => {
    const y = 375 + index * 106
    const color = row.status === 'up' ? '#cd4039' : row.status === 'down' ? '#168978' : '#6d8296'
    context.fillStyle = row.status === 'up' ? '#fff2f0' : row.status === 'down' ? '#ebf8f5' : '#f8fafc'
    context.fillRect(985, y, 394, 106)
    text(`${row.countryName || row.countryCode || ''} · ${row.channel.name}`, xs[0]!, y + 35, 24, '#294660', true, 415)
    text(`${row.channel.provider} · ${row.region}`, xs[0]!, y + 69, 18, '#617b94', false, 415)
    text(row.rollback ? '回滚发布' : row.importBatchId ? `原始批次 ${row.importBatchId}` : row.channel.attribute, xs[0]!, y + 94, 17, '#617b94', false, 415)
    text(row.before?.price == null ? '—' : `¥ ${row.before.price.toFixed(2)}`, xs[1]!, y + 39, 27)
    text(row.before ? `V${row.before.number} · ${row.before.date}` : '无历史基准', xs[1]!, y + 76, 17, '#617b94')
    text(row.after.price == null ? '—' : `¥ ${row.after.price.toFixed(2)}`, xs[2]!, y + 39, 30, '#294660', true)
    text(`V${row.after.number} · ${row.after.date}`, xs[2]!, y + 76, 17, '#617b94')
    text(updateAmount(row), xs[3]!, y + 43, 27, color, true)
    if (row.reason) text(row.reason, xs[3]!, y + 79, 17, '#617b94', false, 367)
    text(updatePercent(row), xs[4]!, y + 43, 25, color)
    context.strokeStyle = '#e0e8f0'; context.beginPath(); context.moveTo(40, y + 106); context.lineTo(1560, y + 106); context.stroke()
  })
  text('按所选重量比较同一渠道 / 国家 / 分区的已发布价格，含渠道原生固定费。', 40, canvas.height - 65, 19, '#617b94')
  text('不含财务折扣、关税及其他附加费。首次记录及无适用价不计入涨跌。', 40, canvas.height - 30, 19, '#617b94')
  return new Promise((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('概览图生成失败')), 'image/png'))
}

/** Large overviews export as numbered PNGs in one ZIP, avoiding browser canvas height limits and omitted rows. */
export async function downloadPriceUpdateOverview(data: OverviewExport): Promise<void> {
  if (!data.rows.length) return
  const pages = Math.ceil(data.rows.length / pageSize)
  const name = `价格更新概览-${data.country}-${data.weight}kg-${publicationTime(data.batch.publishedAt).replace(/[: ]/g, '-')}`
  let blob: Blob
  if (pages === 1) blob = await renderPage(data, data.rows, 1, 1)
  else {
    const { default: JSZip } = await import('jszip')
    const zip = new JSZip()
    for (let page = 0; page < pages; page++) {
      const image = await renderPage(data, data.rows.slice(page * pageSize, (page + 1) * pageSize), page + 1, pages)
      zip.file(`${name}-${String(page + 1).padStart(3, '0')}.png`, await image.arrayBuffer())
    }
    blob = await zip.generateAsync({ type: 'blob' })
  }
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url; link.download = `${name}.${pages === 1 ? 'png' : 'zip'}`
  document.body.append(link); link.click(); link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
