// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import View from './QuotationRecordsView.vue'
import { authState } from '@/data/authStore'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
import { quotationDetailsCsv } from '@/data/quotationAnalytics'
const mocks=vi.hoisted(()=>({page:vi.fn(),get:vi.fn()}))
vi.mock('@/data/quotationRecordQuery',()=>({loadRecordPage:mocks.page,loadFilteredRecords:vi.fn(),loadRecord:vi.fn(),recentRecordDates:()=>({startDate:'',endDate:''})}))
vi.mock('@/services/http',()=>({api:{get:mocks.get},setRequestAccount:vi.fn()}))
vi.mock('@/data/purchaseStore',()=>({loadPurchaseProducts:()=>Promise.resolve([])}))
vi.mock('@/services/customerQuoteSheetRenderer',()=>({renderCustomerQuoteSheet:vi.fn().mockResolvedValue([]),copyQuoteSheetImage:vi.fn()}))
vi.mock('vue-router',()=>({useRouter:()=>({push:vi.fn()}),useRoute:()=>({query:{}})}))
let app:App
const flush=async()=>{for(let i=0;i<10;i++){await nextTick();await Promise.resolve()}}
const row=()=>normalizeQuotationRecord({id:'fob',no:'QT-FOB',quoteMode:'fob',primarySku:'FOB-SKU',productCategory:'内裤',customerName:'客户甲',salespersonAccount:'ME',quoteOptions:[],_version:0,financeReviewStatus:'approved',fob:{schemaVersion:1,rate:6.7,quantity:26,displayMode:'tiers',policy:{scope:'single-price',calculation:'before-coefficient'},product:{sku:'FOB-SKU',category:'内裤',source:'fob',weight:'60 g',notices:[],parsed:{minOrderQty:1,orderMultiple:1,priceTiers:[],freight:{quantity:100,totalFreightCny:0,unitFreightCny:0,estimated:false,basis:'包邮'}}},current:{minQty:26,maxQty:26,unit:'件',declaredUsd:'1.35',undeclaredUsd:'1.37'},ranges:[{minQty:26,maxQty:null,unit:'件',declaredUsd:'1.35',undeclaredUsd:'1.37'}],sheet:{title:'历史报价单',agent:'销售',date:'9 Oct 2026',quantityLabels:['With declaration','Without declaration'],rows:[{key:'FOB-SKU-0',sku:'FOB-SKU',number:1,quantityRange:'26+ pcs',prices:[1.35,1.37],country:'',provider:'',shippingTime:'',sourceDescription:''}],issues:[],notes:['原始说明']}}})!
afterEach(()=>{app?.unmount();document.body.innerHTML='';authState.current=null;authState.permissions=[];vi.useRealTimers();vi.clearAllMocks()})
it.each(['employee','super_admin'] as const)('shows the same FOB snapshot and filter for %s without channel-specific actions',async role=>{
  vi.useFakeTimers();authState.current={id:'me',name:'ME',account:'ME',role,status:'enabled',mustChangePassword:false,passwordUpdatedAt:''};authState.permissions=role==='employee'?['myRecords','quote']:['allRecords','quote']
  mocks.page.mockResolvedValue({items:[row()],page:0,size:10,total:1,totalPages:1,summary:{pending:1,won:0,lost:0,total:1},countries:[]});mocks.get.mockResolvedValue([row()])
  const host=document.createElement('div');document.body.append(host);app=createApp(View,{scope:role==='employee'?'mine':'company'});app.component('RouterLink',{template:'<a><slot /></a>'});app.mount(host);await flush()
  expect(document.querySelector('.route-summary')?.textContent).toContain('FOB批发报价');expect(document.querySelector('.route-summary')?.textContent).not.toContain('1国')
  expect(document.querySelector('.difference-cell')?.textContent).toContain('1.37')
  const select=document.querySelector<HTMLSelectElement>('[aria-label="报价类型筛选"]')!;select.value='fob';select.dispatchEvent(new Event('change',{bubbles:true}));await vi.advanceTimersByTimeAsync(300);await flush()
  expect(mocks.page.mock.calls.at(-1)?.[1]).toMatchObject({quoteMode:'fob'})
  document.querySelector<HTMLButtonElement>('.difference-cell')!.click();await flush()
  expect(document.querySelector('[aria-label="FOB历史报价快照"]')).not.toBeNull();expect(document.querySelector('.record-drawer')?.textContent).not.toContain('首选方案')
  expect(document.querySelector('.record-drawer')?.textContent).toContain('原始说明');expect(document.querySelector('.record-drawer')?.textContent).not.toContain('保存FOB报价')
})
it('exports both FOB prices and source category without changing ordinary export columns',()=>{
  const csv=quotationDetailsCsv([row()],[]);expect(csv).toContain('FOB不报关单价');expect(csv).toContain('FOB（批发）报价');expect(csv).toContain('内裤');expect(csv).toContain('1.37')
  expect(quotationDetailsCsv([{...row(),quoteMode:'single',fob:undefined}],[])).not.toContain('FOB不报关单价')
})
