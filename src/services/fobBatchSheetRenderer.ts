import type { CustomerQuoteSheet } from '@/data/customerQuoteSheet'
import type { QuoteSheetImage } from './customerQuoteSheetRenderer'

const SKU_PALETTES = [
  { background: '#f0f6fd', label: '#dceafb', border: '#7b9fc6' },
  { background: '#f0f8f3', label: '#deeee4', border: '#7ca58a' },
] as const

// A dedicated layout keeps ordinary customer sheets unchanged. Prices are rendered, never recalculated.
export async function renderFobBatchSheet(sheet: CustomerQuoteSheet, cancelled: () => boolean): Promise<QuoteSheetImage[]> {
  const images: QuoteSheetImage[] = [], width = 1536, margin = 40, columns = [40,125,465,756,1126,1496]
  // Assign colors before pagination so a SKU keeps its color on continuation pages.
  // Only adjacent rows merge: preserve the saved row order and every quoted quantity.
  let groupIndex = -1
  const rowPalettes = sheet.rows.map((row, index) => {
    if (index === 0 || row.sku !== sheet.rows[index - 1]!.sku) groupIndex++
    return SKU_PALETTES[groupIndex % SKU_PALETTES.length]!
  })
  for (let offset=0;offset<sheet.rows.length;offset+=20) {
    if(cancelled())return []
    const rows=sheet.rows.slice(offset,offset+20),canvas=document.createElement('canvas'),ctx=canvas.getContext('2d')
    if(!ctx)throw new Error('当前浏览器无法生成报价图片')
    const wrap=(text:string,max:number,font='22px Arial, sans-serif')=>{ctx.font=font;const result:string[]=[];let line='';for(const c of text){if(c==='\n'||(line&&ctx.measureText(line+c).width>max)){result.push(line);line=c==='\n'?'':c}else line+=c}if(line)result.push(line);return result}
    const font='22px Arial, sans-serif', rowHeights=rows.map(r=>Math.max(58,wrap(r.sku || '—',320, 'bold 22px Arial, sans-serif').length*27+16,wrap(r.quantityRange||'',271).length*27+16))
    const notes=(sheet.notes??[]).flatMap(n=>wrap(n,1410,'19px Arial, sans-serif'))
    const titleLines=wrap(sheet.title||'JerryFulfillment Quote Sheet',1400,'bold 42px Arial, sans-serif')
    const customerLines=wrap(`Customer / 客户：${sheet.customerName||'—'}`,1400)
    const contactLines=wrap(`By Agent: ${sheet.agent}    Date: ${sheet.date}    WhatsApp: ${sheet.whatsapp||'—'}`,1400)
    const headerBottom=142+titleLines.length*50+customerLines.length*28+contactLines.length*28
    const bottom=headerBottom+100+rowHeights.reduce((a,b)=>a+b,0), last=offset+rows.length===sheet.rows.length
    const imageHeight=bottom+(last?80+notes.length*25:20)+50
    if(imageHeight>15625)throw new Error('报价单内容过长，请缩短说明后重试')
    canvas.width=width;canvas.height=imageHeight
    ctx.fillStyle='#fff';ctx.fillRect(0,0,width,canvas.height);ctx.textBaseline='middle'
    const text=(value:string,x:number,y:number,font= '22px Arial, sans-serif',color='#183951')=>{ctx.fillStyle=color;ctx.font=font;ctx.textAlign='left';ctx.fillText(value,x,y)}
    titleLines.forEach((line,i)=>text(line,margin,55+i*50,'bold 42px Arial, sans-serif','#102953'))
    let y=65+titleLines.length*50;text('FOB 批量报价单 / Wholesale Quotation',margin,y,'24px Arial, sans-serif');y+=42
    customerLines.forEach(line=>{text(line,margin,y);y+=28});contactLines.forEach(line=>{text(line,margin,y);y+=28})
    text(`Currency: USD    ·    ${new Set(sheet.rows.map(r=>r.sku)).size} SKUs`,margin,y+8,'20px Arial, sans-serif')
    const head=headerBottom
    ctx.fillStyle='#e8f2ff';ctx.fillRect(margin,head,width-margin*2,100)
    const center=(value:string,col:number,top:number,height:number,bold=false)=>{const lines=wrap(value,columns[col+1]!-columns[col]!-20,`${bold?'bold ':''}${font}`);ctx.fillStyle=col>=3?'#144c88':'#193b53';ctx.font=`${bold?'bold ':''}${font}`;ctx.textAlign='center';lines.forEach((line,i)=>ctx.fillText(line,(columns[col]!+columns[col+1]!)/2,top+height/2+(i-(lines.length-1)/2)*27))}
    const labels=['No.','SKU','Quantity / 数量',...sheet.quantityLabels.map(label=>`${label}\n${label==='With declaration'?'报关':'不报关'} USD/单位`)]
    labels.forEach((label,i)=>center(label,i,head,100,true));y=head+100
    const groups: Array<{ sku: string; top: number; height: number; palette: typeof SKU_PALETTES[number] }> = []
    rows.forEach((r,i)=>{const h=rowHeights[i]!, palette=rowPalettes[offset+i]!
      if (i === 0 || r.sku !== rows[i-1]!.sku) groups.push({ sku: r.sku || '—', top: y, height: 0, palette })
      groups[groups.length-1]!.height += h
      ctx.fillStyle=palette.background;ctx.fillRect(margin,y,width-margin*2,h)
      const values=[String(offset+i+1).padStart(2,'0'),r.sku || '—',r.quantityRange||'',...r.prices.map(p=>p==null?'—':`$${p.toFixed(2)}`)]
      values.forEach((v,j)=>{ if(j!==1)center(v,j,y,h,j>=3) });y+=h
      ctx.strokeStyle='#dce5ee';ctx.beginPath();ctx.moveTo(margin,y);ctx.lineTo(width-margin,y);ctx.stroke()})
    ctx.strokeStyle='#dce5ee';for(const x of columns){ctx.beginPath();ctx.moveTo(x,head);ctx.lineTo(x,bottom);ctx.stroke()}
    groups.forEach(group => {
      // Cover internal grid lines in the SKU column and center one label per page segment.
      ctx.fillStyle=group.palette.label
      ctx.fillRect(columns[1]!+1,group.top+1,columns[2]!-columns[1]!-2,group.height-2)
      center(group.sku,1,group.top,group.height,true)
      ctx.fillStyle=group.palette.border;ctx.fillRect(margin,group.top,5,group.height)
      ctx.strokeStyle=group.palette.border;ctx.lineWidth=2
      ctx.beginPath();ctx.moveTo(margin,group.top);ctx.lineTo(width-margin,group.top);ctx.stroke()
      ctx.lineWidth=1
    })
    if(last){text('Notes / 报价说明',margin,bottom+36,'bold 23px Arial, sans-serif');notes.forEach((line,i)=>text(line,margin,bottom+76+i*25,'19px Arial, sans-serif'))}
    text(`Page ${Math.floor(offset/20)+1} / ${Math.ceil(sheet.rows.length/20)}`,width-210,canvas.height-25,'19px Arial, sans-serif')
    const blob=await new Promise<Blob>((resolve,reject)=>canvas.toBlob(b=>b?resolve(b):reject(new Error('报价图片生成失败')),'image/png'))
    images.push({blob,width,height:canvas.height,firstRow:offset,lastRow:offset+rows.length-1});canvas.width=0;canvas.height=0
  }
  return images
}
