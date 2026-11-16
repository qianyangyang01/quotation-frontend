// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import View from './QuotationSystemView.vue'
import { api } from '@/services/http'
import { authState, roleDefinitions } from '@/data/authStore'
import { clearFinanceSettingsCache, financeSettingVersions } from '@/services/financeSettings'
import { replaceLogisticsRules, type LogisticsRule } from '@/data/logistics'
import { loadPublishedLogisticsManifest, loadPublishedLogisticsRules } from '@/data/publishedLogisticsRepository'
import type { QuotationDraftPayload } from '@/services/quotationDrafts'
import type { BatchSingleItem } from '@/services/batchSingleQuotation'
import { mergeBatchShipping } from '@/services/batchSingleQuotation'
import { defaultWomensPricing } from '@/data/womensPricing'

vi.mock('vue-router', () => ({ RouterLink: { template: '<a><slot /></a>' }, useRoute: () => ({ query: {} }), useRouter: () => ({ replace: vi.fn() }), onBeforeRouteLeave: vi.fn() }))
vi.mock('@/services/quotationSync', async original => ({ ...await original<typeof import('@/services/quotationSync')>(), startQuotationSync: vi.fn(() => vi.fn()) }))
vi.mock('@/services/customerQuoteSheetRenderer', async original => ({ ...await original<typeof import('@/services/customerQuoteSheetRenderer')>(), preloadQuoteSheetAssets: vi.fn().mockResolvedValue([]) }))
vi.mock('@/data/publishedLogisticsRepository', async original => ({
  ...await original<typeof import('@/data/publishedLogisticsRepository')>(),
  loadPublishedLogisticsManifest: vi.fn(), loadPublishedLogisticsRules: vi.fn(),
}))
const rules = Array.from({ length: 15 }, (_, i) => ({
  id: 9910 + i, name: `渠道${i}`, billingVerified: true, status: '启用', logisticsChannelId: `channel-${i}`, logisticsVersionId: `version-${i}`,
  relations: [{ carrier: '燕文', channel: `测试${i}`, channelCode: `TEST${i}` }],
  prices: [{ areaName: '美国', countryCode: 'US', weightFromKg: 0, weightToKg: 100, pricingModel: 'per-kg', quoteReady: true, minChargeWeightKg: 0, startWeightKg: 0, firstWeightKg: 0, firstWeightPrice: 0, nextWeightKg: 0, nextWeightPrice: 0, pricePerKg: 50 + i, registrationFee: 5,
    intervalWeightKg: 0, intervalPrice: 0, surcharge: 0, fuelSurchargeRate: 0, volumetric: false, zoneName: '', prohibitedMarks: '', allowedMarks: '' }],
})) as unknown as LogisticsRule[]
const fees = { countries: [{ country: '美国', fixedFeeUsd: .3, selected: true, enabled: true, sortOrder: 1 }], providers: [{ provider: '燕文', mode: 'taxable', selected: true, channels: [] }], updatedAt: 'test' }
const finance = {
  'country-classification': { value: [{country:'美国',enabled:true,stage:'common',sortOrder:1}], _version: 1 },
  'channel-policies': { value: [{ id: '普货', category: '普货', enabled: true, countryRules: [{ country: '美国', allowedChannels: rules.map(rule => `${rule.id}::燕文::${rule.relations[0]!.channelCode}`) }] }], _version: 1 },
  'customer-grades': { value: [{ grade: 'S', coefficient: 1.2, enabled: true }], _version: 1 },
  'exchange-rate': { value: { usdCny: 6, eurUsd: 1.1, updatedAt: 'test' }, _version: 1 },
  'tax-settings': { value: fees, _version: 1 }, 'surcharge-settings': { value: fees, _version: 1 },
  'customer-operation-fees': { value: { customers: [] }, _version: 1 },
  'womens-pricing': { value: defaultWomensPricing(), _version: 1 },
}
let app: App, host: HTMLDivElement
afterEach(() => { app?.unmount(); host?.remove(); vi.restoreAllMocks(); clearFinanceSettingsCache(); replaceLogisticsRules([]); authState.current = null; authState.permissions = [] })

it('keeps duplicate weights independent, recalculates added quantities and never reads purchase records', async () => {
  clearFinanceSettingsCache(); vi.clearAllMocks()
  authState.current = {id:'test-admin',name:'管理员',account:'ADMIN',role:'super_admin',status:'enabled',mustChangePassword:false,passwordUpdatedAt:''}
  authState.permissions = [...roleDefinitions.find(r => r.key === 'super_admin')!.permissions]
  vi.mocked(loadPublishedLogisticsManifest).mockResolvedValue({verified:true,manifest:{revision:'test'}} as never)
  vi.mocked(loadPublishedLogisticsRules).mockImplementation(async()=>{replaceLogisticsRules(rules);return {verified:true,revision:'test',rules,source:'network'}})
  const get = vi.spyOn(api,'get').mockImplementation(async path=>{
    if(path==='/finance-settings')return finance
    if(path==='/quotation-readiness')return {ready:false,purchase:{ready:false},logistics:{ready:true},finance:{ready:true},missing:['无采购数据']}
    if(path==='/quotation-templates')return []
    if(path.startsWith('/quotation-sync?'))return {purchaseVersions:{},financeVersions:financeSettingVersions(),logisticsRevision:'test'}
    if(path==='/quotation-sync/logistics')return {revision:'test'}
    throw new Error('Unexpected API '+path)
  })
  const post=vi.spyOn(api,'post').mockRejectedValue(new Error('No save permitted'))
  const entries=Array.from({length:20},(_,i)=>({id:String(i+1),weight:i<2?'200':String(100+i*20),note:i<2?'相同备注':'备注'+i,selected:true}))
  host=document.createElement('div');document.body.append(host)
  app=createApp(View,{batchCapture:true,batchShipping:true,batchSize:20,batchSkus:entries.map(row=>row.id),shippingEntries:entries})
  const vm=app.mount(host)
  const state=(vm.$ as unknown as {setupState:{draftReady:boolean;customerName:string;quoteMatrixMode:string;draftPayload:()=>QuotationDraftPayload;calculateBatchItem:(id:string,conditions:QuotationDraftPayload,quantities:number[])=>Promise<BatchSingleItem>}}).setupState
  await vi.waitFor(()=>expect(state.draftReady).toBe(true));
  expect(host.textContent).not.toContain('采购阶梯')
  expect(host.textContent).not.toContain('商品与重量')
  expect(host.textContent).not.toContain('样例预览与整批生成')
  expect(host.textContent).toContain('加载渠道')
  state.customerName='批量代发回归';state.quoteMatrixMode='common';await nextTick()
  const conditions=state.draftPayload()
  conditions.commonSelections=rules.slice(0,2).map(rule=>({country:'美国',quoteRegion:'',channelKey:rule.id+'::燕文::'+rule.relations[0]!.channelCode,rule:rule.name,carrier:'燕文',transport:rule.relations[0]!.channel}))
  const first=await state.calculateBatchItem('1',conditions,[1,2,3])
  const duplicate=await state.calculateBatchItem('2',conditions,[1,2,3,5])
  expect(duplicate.input.systemQuantityQuotes!.quantities).toEqual([1,2,3,5])
  expect(duplicate.input.systemQuantityQuotes!.rows.every(row=>row.prices[3]!>0)).toBe(true)
  expect(first.input.systemQuantityQuotes!.quantities).toEqual([1,2,3])
  const items:BatchSingleItem[]=[]
  for(const row of entries)items.push(await state.calculateBatchItem(row.id,conditions,[1,2,3,5]))
  expect(mergeBatchShipping(items).items).toHaveLength(20)
  expect(new Set(items.map(row=>row.sheet.rows[0]!.sku)).size).toBe(20)
  for(const item of items) {
    expect(item.input.primarySku).toBe('');expect(item.input.quoteMode).toBe('shipping-only')
    expect(item.input.manualPricing!.costCny).toBe(0)
    for(const option of item.input.quoteOptions!)expect(option.logisticsSamples!.find(sample=>sample.quantity===5)!.input.weightKg).toBeCloseTo(item.input.manualPricing!.weightGrams*5/1000)
  }
  expect(get.mock.calls.some(([path])=>path.includes('/purchase-products'))).toBe(false)
  expect(post).not.toHaveBeenCalled()
},60000)
