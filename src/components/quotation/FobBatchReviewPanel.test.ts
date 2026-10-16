// @vitest-environment happy-dom
import {createApp,nextTick,type App} from 'vue'
import {afterEach,expect,it,vi} from 'vitest'
import Panel from './FobBatchReviewPanel.vue'
import Detail from './FobRecordDetail.vue'
import {reviewFobItems} from '@/services/fobCorrections'
import {normalizeQuotationRecord} from '@/data/quotationRecords'
import {fobBatchSnapshot} from '@/services/fobBatchQuotation'
import {FOB_SMALL_ORDER_POLICY,type FobQuoteProduct} from '@/services/fobQuotation'
import {fobBatchDealOptions} from '@/services/fobQuotationRecords'
import {setPricingTestPermissions,clearPricingTestPermissions} from '@/test/pricingPermissions'
vi.mock('@/services/fobCorrections',async original=>({...await original<typeof import('@/services/fobCorrections')>(),reviewFobItems:vi.fn()}))
const delivered=vi.hoisted(()=>({rows:[] as string[]}))
vi.mock('./FobBatchSheetExports.vue',()=>({default:{props:['sheet'],setup(p:{sheet:{rows:{sku:string}[]}}){delivered.rows=p.sheet.rows.map(r=>r.sku)},template:'<div data-export />'}}))
vi.mock('./FobQuoteSheet.vue',()=>({default:{template:'<div data-sheet />'}}))
let app:App|undefined
function record(){
 const product:FobQuoteProduct={sku:'SKU0',source:'fob',category:'日用',weight:'50g',updatedAt:'v1',notices:[],parsed:{minOrderQty:3,orderMultiple:1,priceTiers:[{minQty:3,maxQty:null,unitPriceCny:3.9,unit:'件'}],freight:{quantity:100,totalFreightCny:17,unitFreightCny:.17,basis:'100件运费17÷100',estimated:false}}}
 const fob=fobBatchSnapshot([0,1,2].map(index=>({id:index+1,sku:`SKU${index}`,quantity:'3',selected:true,formatError:'',product:{...product,sku:`SKU${index}`}})),'quantity',6.7,FOB_SMALL_ORDER_POLICY,{title:'Saved',agent:'A',date:'2026-10-11',whatsapp:''})
 return normalizeQuotationRecord({id:'id',no:'QT',_version:0,_reviewVersion:2,quoteMode:'fob',financeReviewStatus:'reviewing',financeReviewClaimedAccount:'ADMIN',fob,fobItemReviews:[{index:0,status:'pending',reason:''},{index:1,status:'rejected',reason:'旧原因'},{index:2,status:'approved',reason:''}]})!
}
const tick=async()=>{for(let i=0;i<10;i++){await nextTick();await Promise.resolve()}}
function button(text:string){return [...document.querySelectorAll('button')].find(b=>b.textContent?.includes(text))!}
async function panel(row=record(),stale=false){setPricingTestPermissions();const host=document.createElement('div');document.body.append(host);app=createApp(Panel,{record:row,state:stale?{...row,_reviewVersion:3}:row,account:'ADMIN',canReview:true,busy:false});app.mount(host);await tick()}
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.clearAllMocks();clearPricingTestPermissions();delivered.rows=[]})
it('opens a return dialog, requires a category with an optional reason and submits only selected items',async()=>{
 const row=record();await panel(row);document.querySelector<HTMLInputElement>('[aria-label="选择审核 SKU0 第1项"]')!.click();await tick();button('退回所选').click();await tick();button('确认退回').click();await tick();expect(reviewFobItems).not.toHaveBeenCalled();expect(document.body.textContent).toContain('请为每个退回SKU选择异常类型')
 const input=document.querySelector<HTMLTextAreaElement>('[aria-label=第1项退回原因]')!;input.value='   ';input.dispatchEvent(new Event('input',{bubbles:true}));await tick();const type=document.querySelector<HTMLSelectElement>('[aria-label="第1项异常类型"]')!;expect(type.value).toBe('');type.value='other';type.dispatchEvent(new Event('change'));await nextTick();vi.mocked(reviewFobItems).mockResolvedValue(row);button('确认退回').click();await tick();expect(reviewFobItems).toHaveBeenCalledTimes(1);expect(reviewFobItems).toHaveBeenCalledWith(row,'return',[{index:0,reason:'其他 / 原因待核实，请核实异常原因后再归类',exceptionType:'other'}],'rejected','')
})
it('cannot approve an uncorrected return and cannot act against a stale review',async()=>{
 await panel();document.querySelector<HTMLInputElement>('[aria-label="选择审核 SKU1 第2项"]')!.click();await tick();expect(button('确认审核').disabled).toBe(true)
 app!.unmount();document.body.innerHTML='';await panel(record(),true);expect(button('退回所选').disabled).toBe(true);expect(document.body.textContent).toContain('报价或审核已变化')
})
it('exports and offers deal options for approved items only without mutating the saved sheet',async()=>{
 setPricingTestPermissions(['quote','myRecords']);const row=record(),before=JSON.stringify(row);const host=document.createElement('div');document.body.append(host);app=createApp(Detail,{record:row,state:row});app.mount(host);await tick()
 expect(delivered.rows).toEqual(['SKU2']);expect(fobBatchDealOptions(row).map(o=>o.id)).toEqual(['fob:2:declared','fob:2:undeclared']);expect(JSON.stringify(row)).toBe(before);expect(document.body.textContent).not.toContain('采购成本快照')
})
it('blocks customer exports when another account has changed the review',async()=>{
 const row=record(),host=document.createElement('div');document.body.append(host);app=createApp(Detail,{record:row,state:{...row,_reviewVersion:3}});app.mount(host);await tick();expect(document.querySelector('[data-export]')).toBeNull();expect(document.querySelector('[data-sheet]')).toBeNull();expect(document.body.textContent).toContain('审核状态已变化')
})
it('keeps cross-page selection and return drafts through paging and filtering',async()=>{
 const row=record(),item=row.fob!.items![0]!
 row.fob!.items=Array.from({length:45},(_,index)=>({...item,product:{...item.product,sku:`SKU${index}`}}))
 row.fobItemReviews=Array.from({length:45},(_,index)=>({index,status:'pending',reason:''}))
 await panel(row)
 expect(document.querySelectorAll('tbody')).toHaveLength(20)
 document.querySelector<HTMLInputElement>('[aria-label="选择审核 SKU0 第1项"]')!.click();await tick()
 button('退回所选').click();await tick()
 const reason=document.querySelector<HTMLTextAreaElement>('[aria-label="第1项退回原因"]')!;reason.value='待核对';reason.dispatchEvent(new Event('input',{bubbles:true}));await tick()
 button('返回修改').click();button('下一页').click();await tick()
 document.querySelector<HTMLInputElement>('[aria-label="选择审核 SKU20 第21项"]')!.click();await tick()
 expect(document.body.textContent).toContain('本页 1 项，其他页 1 项')
 const search=document.querySelector<HTMLInputElement>('[aria-label="审核SKU搜索"]')!;search.value='SKU44';search.dispatchEvent(new Event('input',{bubbles:true}));await tick()
 expect(document.querySelectorAll('tbody')).toHaveLength(1);expect(document.body.textContent).toContain('2 项被筛选隐藏')
 button('退回所选').click();await tick()
 expect(document.querySelector<HTMLTextAreaElement>('[aria-label="第1项退回原因"]')!.value).toBe('待核对')
 expect(document.querySelector('[aria-label="第21项退回原因"]')).not.toBeNull()
})
it('persists the selected exemption conclusion and keeps FOB channel exemption disabled',async()=>{
 const row=record();await panel(row)
 expect(document.querySelector<HTMLInputElement>('.unavailable input')!.disabled).toBe(true)
 document.querySelector<HTMLInputElement>('[aria-label="选择审核 SKU0 第1项"]')!.click()
 document.querySelector<HTMLInputElement>('input[value="logistics-exempt"]')!.click();await tick()
 vi.mocked(reviewFobItems).mockResolvedValue(row);button('确认审核').click();await tick()
 expect(reviewFobItems).toHaveBeenCalledWith(row,'approve',[{index:0,reason:''}],'logistics-exempt','')
})
it('selects the entire filtered set explicitly while page selection remains bounded',async()=>{
 const row=record(),item=row.fob!.items![0]!
 row.fob!.items=Array.from({length:45},(_,index)=>({...item,product:{...item.product,sku:`SKU${index}`}}))
 row.fobItemReviews=Array.from({length:45},(_,index)=>({index,status:'pending',reason:''}));await panel(row)
 document.querySelector<HTMLInputElement>('[aria-label="全选本页审核商品"]')!.click();await tick();expect(document.body.textContent).toContain('已选 20 项')
 button('选择全部筛选结果').click();await tick();expect(document.body.textContent).toContain('已选 45 项')
 const select=document.querySelector<HTMLSelectElement>('[aria-label="每页审核SKU数"]')!;select.value='10';select.dispatchEvent(new Event('change',{bubbles:true}));await tick()
 expect(document.querySelectorAll('tbody')).toHaveLength(10);expect(document.body.textContent).toContain('本页 10 项，其他页 35 项')
})
it('shows saved range amounts without recalculating historical prices and hides costs for sales',async()=>{
 const row=record(),item=row.fob!.items![0]!
 item.tiers=[{minQty:3,maxQty:null,unit:'件',purchaseCny:3.9,taxIncludedCny:4.29,freightCny:.17,costCny:4.46,declaredUsd:'0.76',undeclaredUsd:'0.77',thresholdQty:45}]
 item.current={minQty:3,maxQty:3,unit:'件',declaredUsd:'0.93',undeclaredUsd:'0.95',extraCny:1}
 const original=JSON.stringify(row);await panel(row)
 expect(document.querySelector('tbody')!.textContent).toContain('4.29');expect(document.querySelector('tbody')!.textContent).toContain('0.93');expect(JSON.stringify(row)).toBe(original)
 setPricingTestPermissions(['quote','myRecords']);await tick();expect(document.querySelector('thead')!.textContent).not.toContain('含票价');expect(document.querySelector('tbody')!.textContent).not.toContain('4.29')
})
