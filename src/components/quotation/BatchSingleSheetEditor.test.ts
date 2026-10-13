// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import Editor from './BatchSingleSheetEditor.vue'
import { mergeBatchSingle, type BatchSingleItem } from '@/services/batchSingleQuotation'
import { batchSheetEditorSource } from '@/services/batchSingleSheetEditor'
import { averageScope } from '@/data/quoteChannelAverage'
import { buildQuotationWeightSnapshot } from '@/data/quotationWeightSnapshot'

vi.mock('@/services/customerQuoteSheetRenderer',async original=>({...await original<typeof import('@/services/customerQuoteSheetRenderer')>(),preloadQuoteSheetAssets:vi.fn().mockResolvedValue([])}))
let app:App, editor:InstanceType<typeof Editor>
const button=(text:string)=>[...document.querySelectorAll('button')].find(button=>button.textContent?.trim()===text)!
export function item(sku:string):BatchSingleItem {
 const options=['a','b'].map((id,i)=>({id,quoteSheetKey:id,country:'US',carrier:'4PX',channel:id,rule:'test',eta:'7-12 working days',quote1Usd:5+i,quote2Usd:10+i,quote3Usd:15+i,quoteCustomUsd:null}))
 const snapshot={quantities:[1,2,3],rows:options.map(option=>({optionId:option.id,prices:[option.quote1Usd,option.quote2Usd,option.quote3Usd]}))}
 return {input:{quoteMode:'single',primarySku:sku,customerName:'客户',customerGrade:'S',exchangeRate:6.7,commissionThreshold:1,quoteOptions:options,customerQuote:snapshot,systemQuantityQuotes:snapshot,weightSnapshot:buildQuotationWeightSnapshot([{sku,quantityPerSet:1,baseWeightKg:0.1}],0,[1,2,3])},conditions:{},sheet:{agent:'QA',date:'12 Oct 2026',quantityLabels:['1 pc','2 pcs','3 pcs'],issues:[],rows:options.map((option,i)=>({key:option.id,sku,number:i+1,country:'United States',provider:'4PX',shippingTime:'7-12 working days',prices:snapshot.rows[i]!.prices,sourceDescription:''}))}} as unknown as BatchSingleItem
}
async function mount(calculateItem?: (sku:string,q:number[])=>Promise<BatchSingleItem>, documentInput=mergeBatchSingle([item('SKU-A'),item('SKU-B')])) {
 const host=document.createElement('div');document.body.append(host);app=createApp(Editor,{document:documentInput,calculateItem});editor=app.mount(host) as unknown as typeof editor;await nextTick();return documentInput
}
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.restoreAllMocks()})
it('restores tools and keeps same-channel price edits, hidden rows, order and metadata isolated by SKU',async()=>{
 const input=await mount(),before=JSON.stringify(input)
 expect(button('添加图片')).toBeTruthy();expect(button('渠道平均报价')).toBeTruthy();expect(button('＋ 新增列')).toBeTruthy()
 document.querySelector<HTMLButtonElement>('.sheet-price .sheet-cell-text')!.click();await nextTick();await nextTick()
 const prices=[...document.querySelectorAll<HTMLInputElement>('.sheet-price input')]
 prices[0]!.value='8+2';prices[0]!.dispatchEvent(new Event('input'));await nextTick()
 const title=document.querySelector<HTMLInputElement>('[aria-label="报价单标题"]')!;title.value='Custom Quote';title.dispatchEvent(new Event('input'))
 const hide=[...document.querySelectorAll<HTMLButtonElement>('.sheet-editor-scroll button')].find(button=>button.textContent?.trim()==='隐藏')!;hide.click();await nextTick()
 const down=document.querySelector<HTMLButtonElement>('[title="下移"]')!;down.click();await nextTick()
 const saved=await editor.capture()
 expect(saved.items[0]!.input.customerQuote!.rows[0]!.prices[0]).toBe(10)
 expect(saved.items[1]!.input.customerQuote!.rows[0]!.prices[0]).toBe(5)
 expect(saved.items[0]!.input.customerQuote!.hiddenOptionIds).toEqual(['a'])
 expect(saved.sheet.rows).toHaveLength(3);expect(saved.sheet.rows[0]!.sku).toBe('SKU-B')
 expect(saved.sheet.title).toBe('Custom Quote');expect(saved.items.every(item=>item.sheet.title==='Custom Quote')).toBe(true)
 expect(JSON.stringify(input)).toBe(before)
 const restored=batchSheetEditorSource(saved)
 expect(restored.initialQuote.hiddenOptionIds).toHaveLength(1)
 expect(restored.initialSheet.title).toBe('Custom Quote')
 expect(averageScope(restored.rows[0]!)).not.toBe(averageScope(restored.rows[2]!))
})
it('recalculates added quantities per SKU and saves all rows despite a search filter',async()=>{
 const calculate=vi.fn(async(sku:string,quantities:number[])=>{const result=item(sku);result.input.systemQuantityQuotes={quantities,rows:result.input.quoteOptions!.map(option=>({optionId:option.id,prices:quantities.map(q=>q*(sku==='SKU-A'?5:7))}))};return result})
 await mount(calculate);button('＋ 新增列').click();await nextTick()
 const quantity=[...document.querySelectorAll<HTMLInputElement>('.sheet-quantity input')].at(-1)!;quantity.value='8';quantity.dispatchEvent(new Event('input'));await nextTick()
 await vi.waitFor(()=>expect(calculate).toHaveBeenCalledTimes(2));await nextTick()
 const search=document.querySelector<HTMLInputElement>('[aria-label="搜索报价单 SKU"]')!;search.value='SKU-B';search.dispatchEvent(new Event('input'));await nextTick()
 expect(document.querySelectorAll('.sheet-editor-scroll tbody tr')).toHaveLength(2)
 const saved=await editor.capture();expect(saved.sheet.rows).toHaveLength(4)
 expect(saved.items[0]!.input.customerQuote!.rows[0]!.prices.at(-1)).toBe(40)
 expect(saved.items[1]!.input.customerQuote!.rows[0]!.prices.at(-1)).toBe(56)
 expect(saved.items[0]!.input.weightSnapshot!.quantities.find(row=>row.quantity===8)!.weightKg).toBe(0.816)
})
it('blocks save after quantity lookup failure and supports a retry without clearing edits',async()=>{
 const calculate=vi.fn(async()=>{throw new Error('SKU-B 渠道查询超时')})
 await mount(calculate);button('＋ 新增列').click();await nextTick()
 const quantity=[...document.querySelectorAll<HTMLInputElement>('.sheet-quantity input')].at(-1)!;quantity.value='8';quantity.dispatchEvent(new Event('input'));await nextTick()
 await vi.waitFor(()=>expect(document.body.textContent).toContain('SKU-B 渠道查询超时'))
 await expect(editor.capture()).rejects.toThrow('SKU-B 渠道查询超时')
 expect(button('重试数量计算')).toBeTruthy()
})
it('generates a summary for every SKU in one click and keeps saved ownership isolated',async()=>{
 await mount();button('渠道平均报价').click();await nextTick()
 expect(document.querySelectorAll('[aria-label="平均报价国家"] option')).toHaveLength(3)
 const members=[...document.querySelectorAll<HTMLInputElement>('.average-channels input[type="checkbox"]')]
 expect(members).toHaveLength(2);for(const member of members){member.click();await nextTick()}
 const summary=document.querySelector<HTMLInputElement>('.average-options input[value="summary"]')!;summary.click();await nextTick()
 button('生成平均行').click();await nextTick()
 const saved=await editor.capture()
 expect(saved.items[0]!.input.customerQuote!.averagePlans).toHaveLength(1)
 expect(saved.items[1]!.input.customerQuote!.averagePlans).toHaveLength(1)
 expect(saved.items[1]!.sheet.rows).toHaveLength(1)
 expect(saved.items[0]!.sheet.rows).toHaveLength(1)
 expect(saved.items[0]!.sheet.rows[0]!.prices[0]).toBe(5.5)
 expect(saved.items[0]!.input.customerQuote!.averagePlans![0]!.members.map(member=>member.optionId)).toEqual(['a','b'])
 const restored=batchSheetEditorSource(saved)
 expect(restored.initialQuote.averagePlans![0]!.members.every(member=>member.optionId.includes('SKU-A'))).toBe(true)
})

async function generateAverages() {
 button('渠道平均报价').click();await nextTick()
 for(const member of document.querySelectorAll<HTMLInputElement>('.average-channels input[type="checkbox"]')) {member.click();await nextTick()}
 button('生成平均行').click();await nextTick()
}
it('uses each SKU price, puts averages first, and preserves order and SKU boundaries after reopening',async()=>{
 const a=item('SKU-A'),b=item('SKU-B')
 b.input.systemQuantityQuotes=JSON.parse(JSON.stringify(b.input.systemQuantityQuotes))
 b.input.systemQuantityQuotes!.rows.forEach(row=>{row.prices=row.prices.map(price=>price! * 2)})
 b.input.quoteOptions!.forEach(option=>{option.quote1Usd! *= 2;option.quote2Usd! *= 2;option.quote3Usd! *= 2})
 const input=mergeBatchSingle([a,b]),before=JSON.stringify(input)
 await mount(undefined,input);await generateAverages()
 const saved=await editor.capture()
 expect(saved.items.map(item=>item.sheet.rows[0]!.prices)).toEqual([[5.5,10.5,15.5],[11,21,31]])
 expect(saved.sheet.rows.map(row=>[row.sku,!!row.averageId])).toEqual([
  ['SKU-A',true],['SKU-A',false],['SKU-A',false],['SKU-B',true],['SKU-B',false],['SKU-B',false],
 ])
 expect(saved.items.every(item=>item.input.customerQuote!.rowOrder![0]!.startsWith('average:'))).toBe(true)
 expect(JSON.stringify(input)).toBe(before)
 app.unmount();document.body.innerHTML='';await mount(undefined,saved)
 const rows=[...document.querySelectorAll('.sheet-editor tbody tr')]
 expect(rows.map(row=>row.classList.contains('sheet-average-row'))).toEqual([true,false,false,true,false,false])
 expect(rows.map(row=>row.classList.contains('sheet-sku-start'))).toEqual([false,false,false,true,false,false])
 const reopened=await editor.capture()
 expect(reopened.sheet.rows.map(row=>[row.sku,row.prices])).toEqual(saved.sheet.rows.map(row=>[row.sku,row.prices]))
})
it('generates all 50 SKUs despite pagination and filtering and does not duplicate repeat generations',async()=>{
 await mount(undefined,mergeBatchSingle(Array.from({length:50},(_,i)=>item(`SKU-${i}`))))
 const search=document.querySelector<HTMLInputElement>('[aria-label="搜索报价单 SKU"]')!
 search.value='SKU-49';search.dispatchEvent(new Event('input'));await nextTick()
 await generateAverages()
 expect(document.querySelectorAll('.sheet-editor tbody tr')).toHaveLength(3)
 const first=await editor.capture()
 expect(first.sheet.rows).toHaveLength(150)
 expect(first.items.every(item=>item.input.customerQuote!.averagePlans!.length===1 && !!item.sheet.rows[0]!.averageId)).toBe(true)
 button('生成平均行').click();await nextTick()
 const repeated=await editor.capture()
 expect(repeated.items.map(item=>item.input.customerQuote!.averagePlans)).toEqual(first.items.map(item=>item.input.customerQuote!.averagePlans))
 expect(repeated.sheet.rows).toHaveLength(150)
})
it('reports a missing route and does not leave a partially generated batch',async()=>{
 const a=item('SKU-A'),b=item('SKU-B')
 b.input.quoteOptions=b.input.quoteOptions!.slice(0,1)
 b.input.customerQuote!.rows=b.input.customerQuote!.rows.slice(0,1)
 b.sheet.rows=b.sheet.rows.slice(0,1)
 await mount(undefined,mergeBatchSingle([a,b]));await generateAverages()
 expect(document.body.textContent).toContain('SKU-B 缺少所选渠道')
 expect(document.querySelectorAll('.sheet-average-row')).toHaveLength(0)
 const saved=await editor.capture()
 expect(saved.items.every(item=>!item.input.customerQuote!.averagePlans!.length)).toBe(true)
})

it('rejects a changed pricing basis instead of mixing new purchase prices with the existing sheet', async () => {
 const calculate=vi.fn(async(sku:string)=>{const changed=item(sku);changed.input.exchangeRate=7;return changed})
 await mount(calculate);button('＋ 新增列').click();await nextTick()
 const quantity=[...document.querySelectorAll<HTMLInputElement>('.sheet-quantity input')].at(-1)!
 quantity.value='8';quantity.dispatchEvent(new Event('input'));await nextTick()
 await vi.waitFor(()=>expect(document.body.textContent).toContain('计价资料已变化'))
 await expect(editor.capture()).rejects.toThrow('重新生成整批报价')
})
it('actually retries a failed quantity calculation and preserves prior customer price edits', async () => {
 const calculate=vi.fn(async(sku:string,quantities:number[])=>{const fresh=item(sku);fresh.input.systemQuantityQuotes={quantities,rows:fresh.input.quoteOptions!.map(option=>({optionId:option.id,prices:quantities.map(q=>q*5)}))};return fresh})
 calculate.mockRejectedValueOnce(new Error('临时断网'))
 await mount(calculate)
 const price=await openCell('第 1 行第 1 列美元价格')
 price.value='9.95';price.dispatchEvent(new Event('input'));await nextTick()
 button('＋ 新增列').click();await nextTick()
 const quantity=[...document.querySelectorAll<HTMLInputElement>('.sheet-quantity input')].at(-1)!
 quantity.value='8';quantity.dispatchEvent(new Event('input'));await nextTick()
 await vi.waitFor(()=>expect(document.body.textContent).toContain('临时断网'))
 button('重试数量计算').click()
 await vi.waitFor(()=>expect(document.body.textContent).not.toContain('临时断网'))
 const saved=await editor.capture()
 expect(saved.items[0]!.input.customerQuote!.rows[0]!.prices).toEqual([9.95,10,15,40])
 expect(saved.items[1]!.input.customerQuote!.rows[0]!.prices).toEqual([5,10,15,40])
})

it('serializes rapid quantity edits and captures only the final requested quantity', async () => {
 let finishFirst!:()=>void
 const held=new Promise<void>(resolve=>{finishFirst=resolve})
 const calculate=vi.fn(async(sku:string,quantities:number[])=>{
  if(calculate.mock.calls.length===1) await held
  const fresh=item(sku);fresh.input.systemQuantityQuotes={quantities,rows:fresh.input.quoteOptions!.map(option=>({optionId:option.id,prices:quantities.map(q=>q*5)}))};return fresh
 })
 await mount(calculate)
 button('＋ 新增列').click();await nextTick()
 const quantity=[...document.querySelectorAll<HTMLInputElement>('.sheet-quantity input')].at(-1)!
 quantity.value='8';quantity.dispatchEvent(new Event('input'));await nextTick()
 await vi.waitFor(()=>expect(calculate).toHaveBeenCalledTimes(1))
 await expect(editor.capture()).rejects.toThrow('仍在计算')
 quantity.value='9';quantity.dispatchEvent(new Event('input'));await nextTick()
 finishFirst()
 await vi.waitFor(()=>expect(calculate).toHaveBeenCalledTimes(4))
 await nextTick()
 const saved=await editor.capture()
 expect(saved.items.every(row=>JSON.stringify(row.input.customerQuote!.quantities)==='[1,2,3,9]')).toBe(true)
 expect(saved.items[0]!.input.customerQuote!.rows[0]!.prices.at(-1)).toBe(45)
})
async function openCell(label:string) {
 const trigger=document.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)!
 trigger.click();await nextTick();await nextTick()
 return document.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!
}
async function typeCell(field:HTMLInputElement,value:string) { field.value=value;field.dispatchEvent(new Event('input'));await nextTick() }
async function keyCell(field:HTMLInputElement,key:string) { field.dispatchEvent(new KeyboardEvent('keydown',{key,bubbles:true}));await nextTick();await nextTick() }

it('shows text at rest, confirms a price formula, and cancels only the current cell edit',async()=>{
 await mount()
 expect(document.querySelectorAll('.sheet-editor tbody input')).toHaveLength(0)
 let field=await openCell('第 1 行第 1 列美元价格')
 await typeCell(field,'8+2');await keyCell(field,'Enter')
 expect(document.querySelectorAll('.sheet-editor tbody input')).toHaveLength(0)
 expect(document.querySelector('[aria-label="第 1 行第 1 列美元价格"]')!.textContent).toBe('10.00')
 const before=await editor.capture()
 field=await openCell('第 1 行第 1 列美元价格');await typeCell(field,'99');await keyCell(field,'Escape')
 expect(await editor.capture()).toEqual(before)
 field=await openCell('第 1 行国家');await typeCell(field,'Canada');await keyCell(field,'Escape')
 expect(await editor.capture()).toEqual(before)
 field=await openCell('第 1 行物流商');await typeCell(field,'Custom Carrier');field.blur();await nextTick()
 const saved=await editor.capture()
 expect(saved.sheet.rows[0]!.provider).toBe('Custom Carrier')
 expect(saved.sheet.rows[1]!.provider).toBe('4PX')
})
it('cancels an invalid average edit without changing its saved price or leaving validation errors',async()=>{
 await mount();await generateAverages()
 const before=await editor.capture()
 const label=document.querySelector('.sheet-average-row .sheet-cell-text')!.getAttribute('aria-label')!
 const field=await openCell(label);await typeCell(field,'1/0');await keyCell(field,'Enter')
 expect(document.querySelector('.sheet-average-row input')).not.toBeNull()
 await expect(editor.capture()).rejects.toThrow()
 await keyCell(field,'Escape')
 expect(await editor.capture()).toEqual(before)
})
it('keeps invalid ordinary prices blocked after blur and opens only one editor at a time',async()=>{
 await mount()
 const field=await openCell('第 1 行第 1 列美元价格');await typeCell(field,'1/0');field.blur();await nextTick()
 expect(document.querySelector('[aria-label="第 1 行第 1 列美元价格"]')!.getAttribute('aria-invalid')).toBe('true')
 await expect(editor.capture()).rejects.toThrow()
 const fixed=await openCell('第 1 行第 1 列美元价格');await typeCell(fixed,'12.35');await keyCell(fixed,'Enter')
 await openCell('第 1 行国家');await openCell('第 2 行处理时间')
 expect(document.querySelectorAll('.sheet-editor tbody input')).toHaveLength(1)
 const saved=await editor.capture();expect(saved.sheet.rows[0]!.prices[0]).toBe(12.35)
})
