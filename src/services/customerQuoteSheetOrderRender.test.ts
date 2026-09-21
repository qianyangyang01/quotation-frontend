import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { buildCustomerQuoteSheet, newQuoteSheetEdits, type QuoteSheetColumnKey } from '@/data/customerQuoteSheet'
import { quoteSheetLayout, renderCustomerQuoteSheet } from './customerQuoteSheetRenderer'

const pages: Array<{ text: Array<[string,number,number]>, pictures: unknown[][] }> = []
const photo = { url:'blob:test', name:'test.png', image:{ naturalWidth:100, naturalHeight:100 } as HTMLImageElement }
function data(order?: QuoteSheetColumnKey[], count=1) {
  return buildCustomerQuoteSheet({
    rows:Array.from({length:count},(_,i)=>({country:'US',carrier:'4PX',channelKey:String(i),channelCode:String(i),ruleId:i,rule:'',transport:'',eta:'5-8 days',quote1:21,quote2:37.9,quote3:55.7,quoteCustom:91.4})),
    countries:[],skus:['QY2601661'],edits:{...newQuoteSheetEdits('QA'),whatsapp:'+86 XXX XXXX XXXX',columnOrder:order},customQuantity:5,bundle:false,
  })
}
beforeEach(()=>{
  pages.length=0
  vi.stubGlobal('Image',class { onload?:()=>void; set src(_url:string){queueMicrotask(()=>this.onload?.())} })
  vi.stubGlobal('document',{createElement:()=>{
    const page={text:[] as Array<[string,number,number]>,pictures:[] as unknown[][]};pages.push(page)
    const context=new Proxy({
      measureText:(text:string)=>({width:text.length*11}),
      fillText:(text:string,x:number,y:number)=>page.text.push([text,x,y]),
      drawImage:(...args:unknown[])=>page.pictures.push(args),
    },{get:(target,key)=>target[key as keyof typeof target]??(()=>{})})
    return {width:0,height:0,getContext:()=>context,toBlob:(done:(blob:Blob)=>void)=>done(new Blob(['png']))}
  }})
})
afterEach(()=>vi.unstubAllGlobals())

it('draws the contact on every page and aligns moved prices, fixed columns and the merged product cell',async()=>{
  const order:QuoteSheetColumnKey[]=['prices','sku','country','provider','number','shippingTime','processingTime','product']
  const sheet=data(order,25)
  const images=await renderCustomerQuoteSheet(sheet,()=>false,[photo])
  expect(images.map(image=>[image.firstRow,image.lastRow])).toEqual([[1,24],[25,25]])
  const layout=quoteSheetLayout(4,[],true,order)
  for(const page of pages){
    expect(page.text.filter(call=>call[0]==='WhatsApp: +86 XXX XXXX XXXX')).toHaveLength(1)
    for(const [index,label] of ['1 pc','2 pcs','3 pcs','5 pcs','SKU','Country','Logistics Provider','No.','Shipping Time','Processing','Product'].entries()){
      const cellIndex=index
      const matches=page.text.filter(call=>call[0]===label && call[2]<269)
      expect(matches.length).toBeGreaterThan(0)
      expect(matches[0][1]).toBe((layout.columns[cellIndex]+layout.columns[cellIndex+1])/2)
    }
    for(const [index,price] of ['$21.00','$37.90','$55.70','$91.40'].entries()){
      const matching=page.text.filter(call=>call[0]===price)
      expect(matching.length).toBeGreaterThan(0)
      expect(matching.every(call=>call[1]===(layout.columns[index]+layout.columns[index+1])/2)).toBe(true)
    }
    const photoDraw=page.pictures.find(call=>call[0]===photo.image)!
    expect(photoDraw).toBeTruthy()
    expect(Number(photoDraw[1])).toBeGreaterThan(layout.columns.at(-2)!)
    expect(Number(photoDraw[1])+Number(photoDraw[3])).toBeLessThan(layout.right)
  }
})
it('omits a blank contact and rejects oversized contact text before exporting a clipped image',async()=>{
  const sheet=data();sheet.whatsapp=''
  await renderCustomerQuoteSheet(sheet)
  expect(pages[0].text.some(call=>call[0].startsWith('WhatsApp:'))).toBe(false)
  sheet.whatsapp='W'.repeat(100)
  await expect(renderCustomerQuoteSheet(sheet)).rejects.toThrow('WhatsApp 联系方式过长')
})
it('keeps a two-line custom title above the contact',async()=>{
  const sheet=data()
  sheet.title='A'.repeat(70)
  await renderCustomerQuoteSheet(sheet)
  const titleCalls=pages[0].text.filter(call=>/^A+$/.test(call[0]))
  expect(titleCalls).toHaveLength(2)
  const contact=pages[0].text.find(call=>call[0].startsWith('WhatsApp:'))!
  expect(Math.max(...titleCalls.map(call=>call[2]))+23).toBeLessThan(contact[2]-11)
})
