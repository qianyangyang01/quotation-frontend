// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import View from './QuotationRecordsView.vue'
import { normalizeQuotationRecord } from '@/data/quotationRecords'

const mocks=vi.hoisted(()=>({page:vi.fn(),deleted:vi.fn(),purchases:vi.fn()}))
vi.mock('@/data/quotationRecordQuery',()=>({loadRecordPage:mocks.page,loadFilteredRecords:vi.fn(),loadRecord:vi.fn(),recentRecordDates:()=>({startDate:'',endDate:''})}))
vi.mock('@/data/purchaseStore',()=>({loadPurchaseProducts:mocks.purchases}))
vi.mock('@/services/purchaseForceDeletion',async original=>({...await original<object>(),loadDeletedPurchaseSnapshots:mocks.deleted}))
vi.mock('@/data/authStore',async()=>{const {ref}=await import('vue');return {currentAuthUser:ref({account:'ME',role:'super_admin'}),hasPermission:()=>true}})
vi.mock('@/composables/useQuotationReviewSync',()=>({useQuotationReviewSync:()=>({isMissing:()=>false,markMissing:vi.fn(),stateFor:(r:unknown)=>r,accept:vi.fn(),error:{value:''},poll:vi.fn()})}))
vi.mock('vue-router',()=>({useRouter:()=>({push:vi.fn()}),useRoute:()=>({query:{}})}))
let app:App
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.clearAllMocks();vi.useRealTimers()})
async function mount(snapshotImage='') {
  vi.useFakeTimers()
  const row=normalizeQuotationRecord({id:'old',no:'QT-OLD',customerName:'历史客户',primarySku:'DELETED',productImage:snapshotImage,
    productSummary:'旧商品',systemQuoteCny:88.8,systemQuoteUsd:12,createdAt:'2026-09-20T00:00:00Z',salespersonAccount:'ME'})!
  mocks.page.mockResolvedValue({items:[row],page:0,size:10,total:1,totalPages:1,summary:{pending:1,won:0,lost:0,total:1},countries:[]})
  mocks.purchases.mockResolvedValue([{sku:'DELETED',productImage:'/new-product.png',physicalImage:'',category:'新商品'}])
  mocks.deleted.mockResolvedValue([{sku:'DELETED',productImage:'/archived-product.png',physicalImage:'',category:'旧商品',createdAt:'2026-09-01T00:00:00Z',deletedAt:'2026-10-01T00:00:00Z'}])
  const host=document.createElement('div');document.body.append(host)
  app=createApp(View,{scope:'company'});app.component('RouterLink',{template:'<a><slot /></a>'});app.mount(host)
  for(let i=0;i<6;i++){await Promise.resolve();await nextTick()}
  return row
}
it('renders the archived image for an old quote even when its SKU has since been reused',async()=>{
  const row=await mount()
  expect(document.querySelector('.quote-record-image img')?.getAttribute('src')).toBe('/archived-product.png')
  expect(document.body.textContent).toContain('QT-OLD')
  expect(row.productImage).toBeUndefined();expect(row.systemQuoteCny).toBe(88.8)
  expect(mocks.deleted).toHaveBeenCalledWith(['DELETED'])
})
it('keeps an embedded historical image and does not request a replacement',async()=>{
  await mount('/original-snapshot.png')
  expect(document.querySelector('.quote-record-image img')?.getAttribute('src')).toBe('/original-snapshot.png')
  expect(mocks.deleted).not.toHaveBeenCalled()
})
