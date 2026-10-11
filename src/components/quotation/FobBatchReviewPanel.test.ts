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
it('requires custom reason for each selected return and explicitly sends approve-other-pending choice',async()=>{
 const row=record();await panel(row);document.querySelector<HTMLInputElement>('[aria-label="选择审核 SKU0 第1项"]')!.click();await tick();button('退回所选').click();await tick();expect(reviewFobItems).not.toHaveBeenCalled();expect(document.body.textContent).toContain('请为每个退回SKU填写原因')
 const input=document.querySelector<HTMLTextAreaElement>('textarea')!;input.value='采购克重异常，请更新后查询';input.dispatchEvent(new Event('input',{bubbles:true}));await tick();vi.mocked(reviewFobItems).mockResolvedValue(row);button('退回所选').click();await tick();expect(reviewFobItems).toHaveBeenCalledWith(row,'return',[{index:0,reason:'采购克重异常，请更新后查询'}],true)
})
it('cannot approve an uncorrected return and cannot act against a stale review',async()=>{
 await panel();document.querySelector<HTMLInputElement>('[aria-label="选择审核 SKU1 第2项"]')!.click();await tick();expect(button('通过所选').disabled).toBe(true)
 app!.unmount();document.body.innerHTML='';await panel(record(),true);expect(button('退回所选').disabled).toBe(true);expect(document.body.textContent).toContain('报价或审核已变化')
})
it('exports and offers deal options for approved items only without mutating the saved sheet',async()=>{
 setPricingTestPermissions(['quote','myRecords']);const row=record(),before=JSON.stringify(row);const host=document.createElement('div');document.body.append(host);app=createApp(Detail,{record:row,state:row});app.mount(host);await tick()
 expect(delivered.rows).toEqual(['SKU2']);expect(fobBatchDealOptions(row).map(o=>o.id)).toEqual(['fob:2:declared','fob:2:undeclared']);expect(JSON.stringify(row)).toBe(before);expect(document.body.textContent).not.toContain('采购成本快照')
})
it('blocks customer exports when another account has changed the review',async()=>{
 const row=record(),host=document.createElement('div');document.body.append(host);app=createApp(Detail,{record:row,state:{...row,_reviewVersion:3}});app.mount(host);await tick();expect(document.querySelector('[data-export]')).toBeNull();expect(document.querySelector('[data-sheet]')).toBeNull();expect(document.body.textContent).toContain('审核状态已变化')
})
