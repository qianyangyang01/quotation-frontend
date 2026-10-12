// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { createHash } from 'node:crypto'
import { writeFileSync } from 'node:fs'
import { afterEach, expect, it, vi } from 'vitest'
import View from './QuotationSystemView.vue'
import { api } from '@/services/http'
import { authState, roleDefinitions } from '@/data/authStore'
import { clearFinanceSettingsCache, financeSettingVersions } from '@/services/financeSettings'
import { replaceLogisticsRules, type LogisticsRule } from '@/data/logistics'
import { loadPublishedLogisticsManifest, loadPublishedLogisticsRules } from '@/data/publishedLogisticsRepository'
import type { QuotationDraftPayload } from '@/services/quotationDrafts'
import type { BatchSingleItem } from '@/services/batchSingleQuotation'

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ replace: vi.fn() }), onBeforeRouteLeave: vi.fn() }))
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
}
let app: App, host: HTMLDivElement
afterEach(() => { app?.unmount(); host?.remove(); vi.restoreAllMocks(); clearFinanceSettingsCache(); replaceLogisticsRules([]); authState.current = null; authState.permissions = [] })
it('generates 14 SKUs x 15 channels with independent prices and per-SKU server validation', async () => {
  clearFinanceSettingsCache(); vi.clearAllMocks()
  authState.current = {id:'test-admin',name:'管理员',account:'ADMIN',role:'super_admin',status:'enabled',mustChangePassword:false,passwordUpdatedAt:''}
  authState.permissions = [...roleDefinitions.find(r => r.key === 'super_admin')!.permissions]
  const latency = Number(process.env.BATCH_BENCHMARK_LATENCY || 0)
  const delay = async () => { if (latency) await new Promise(resolve => setTimeout(resolve, latency)) }
  vi.mocked(loadPublishedLogisticsManifest).mockImplementation(async () => { await delay(); return { verified:true,manifest:{revision:'test'} } as never })
  vi.mocked(loadPublishedLogisticsRules).mockImplementation(async () => { await delay(); replaceLogisticsRules(rules); return {verified:true,revision:'test',rules,source:'network'} })
  const get = vi.spyOn(api, 'get').mockImplementation(async path => {
    await delay()
    if (path === '/finance-settings') return finance
    if (path === '/quotation-readiness') return {ready:true,missing:[]}
    if (path === '/quotation-templates') return []
    if (path.startsWith('/purchase-products/')) {
      const sku = path.split('/').at(-1)!, index = Number(sku.replace('SKU',''))
      return {sku,category:'日用品',catalogState:'ready',weightG:100+index*20,minOrderQty:1,purchasePriceCny:12+index,singleFreightCny:0,taxPoint:0,invoiceType:'普票',_version:1,_updatedAt:'test'}
    }
    if (path.startsWith('/quotation-sync?')) return {purchaseVersions:Object.fromEntries(Array.from({length:14},(_,i)=>[`SKU${i}`,'1:test'])),financeVersions:financeSettingVersions(),logisticsRevision:'test'}
    if (path === '/quotation-sync/logistics') return {revision:'test'}
    throw new Error(`Unexpected API ${path}`)
  })
  const post = vi.spyOn(api,'post').mockRejectedValue(new Error('No production-style save allowed'))
  host = document.createElement('div'); document.body.append(host)
  app = createApp(View,{batchCapture:true,batchSkus:Array.from({length:14},(_,i)=>`SKU${i}`),batchSize:14})
  const vm = app.mount(host)
  const state = (vm.$ as unknown as {setupState:{draftReady:boolean;customerName:string;quoteMatrixMode:string;logisticsLoadState:string;draftPayload:()=>QuotationDraftPayload;calculateBatchItem:(sku:string,payload:QuotationDraftPayload,quantities:number[])=>Promise<BatchSingleItem>}}).setupState
  await vi.waitFor(()=>expect(state.draftReady).toBe(true))
  vi.mocked(loadPublishedLogisticsManifest).mockClear(); vi.mocked(loadPublishedLogisticsRules).mockClear()
  state.customerName='批量性能验证'; state.quoteMatrixMode='common'; await nextTick()
  const conditions=state.draftPayload()
  conditions.product.sku='SKU0'; conditions.skuSearch='SKU0'
  conditions.commonSelections=rules.map(rule=>({country:'美国',quoteRegion:'',channelKey:`${rule.id}::燕文::${rule.relations[0]!.channelCode}`,rule:rule.name,carrier:'燕文',transport:rule.relations[0]!.channel}))
  const start=performance.now(), items:BatchSingleItem[]=[]
  for(let i=0;i<14;i++) items.push(await state.calculateBatchItem(`SKU${i}`,conditions,[1,2,3]))
  const elapsedMs=Math.round(performance.now()-start)
  expect(items.flatMap(item=>item.sheet.rows)).toHaveLength(210)
  expect(new Set(items.map(item=>item.sheet.rows[0]!.prices[0])).size).toBe(14)
  expect(get.mock.calls.filter(([path])=>path.startsWith('/purchase-products/'))).toHaveLength(14)
  expect(get.mock.calls.filter(([path])=>path.startsWith('/quotation-sync?'))).toHaveLength(14)
  expect(get.mock.calls.filter(([path])=>path==='/quotation-sync/logistics')).toHaveLength(14)
  const baseline=process.env.BATCH_BENCHMARK_BASELINE==='1'
  expect(loadPublishedLogisticsManifest).toHaveBeenCalledTimes(baseline?14:1)
  expect(loadPublishedLogisticsRules).toHaveBeenCalledTimes(baseline?14:1)
  expect(get.mock.calls.filter(([path])=>path==='/finance-settings')).toHaveLength(baseline?15:1)
  expect(post).not.toHaveBeenCalled()
  const digest=createHash('sha256').update(JSON.stringify(items.map(item=>({input:item.input,rows:item.sheet.rows})))).digest('hex')
  const report={benchmark:'14x15',latencyMs:latency,elapsedMs,digest,manifest:vi.mocked(loadPublishedLogisticsManifest).mock.calls.length,ruleLoads:vi.mocked(loadPublishedLogisticsRules).mock.calls.length,financeLoads:get.mock.calls.filter(([path])=>path==='/finance-settings').length}
  if(process.env.BATCH_BENCHMARK_OUTPUT)writeFileSync(process.env.BATCH_BENCHMARK_OUTPUT,JSON.stringify(report,null,2))
  // A changed country or unverified rule slice must take the original reload path.
  const priorLoads=vi.mocked(loadPublishedLogisticsRules).mock.calls.length
  const changed=JSON.parse(JSON.stringify(conditions)) as QuotationDraftPayload
  changed.commonSelections![0]!.country='英国'
  await state.calculateBatchItem('SKU0',changed,[1,2,3]).catch(()=>undefined)
  expect(loadPublishedLogisticsRules).toHaveBeenCalledTimes(priorLoads+1)
  state.logisticsLoadState='stale'
  await state.calculateBatchItem('SKU0',conditions,[1,2,3])
  expect(loadPublishedLogisticsRules).toHaveBeenCalledTimes(priorLoads+2)
  const differentAttribute=JSON.parse(JSON.stringify(conditions)) as QuotationDraftPayload
  differentAttribute.logisticsAttribute='带电'
  await state.calculateBatchItem('SKU0',differentAttribute,[1,2,3]).catch(()=>undefined)
  expect(loadPublishedLogisticsRules).toHaveBeenCalledTimes(priorLoads+3)
}, 60000)
