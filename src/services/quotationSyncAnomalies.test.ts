import {readFileSync} from 'node:fs'
import ts from 'typescript'
import {expect,it,vi} from 'vitest'
import {ApiError} from './http'
import { changedFinanceSettings, type FinanceSettingVersions } from './financeSettings'
const source=readFileSync(new URL('../views/QuotationSystemView.vue',import.meta.url),'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
const ast=ts.createSourceFile('view.ts',source,ts.ScriptTarget.Latest,true)
function setup(){
  const state={liveVersionCheckSequence:0,activePurchaseSkus:()=>['SKU'],draftSignature:()=> 'A',
    selectedCustomerId:{value:''},customerName:{value:'甲'},customerOperation:{value:{snapshot:{id:'a',name:'甲',feeUsd:1}}},
    hydrateFinanceSettings:vi.fn(async()=>{}),loadCustomerOperationSettings:()=>({}),resolveCustomerOperation:vi.fn(()=>({configured:true,snapshot:{id:'a',name:'甲',feeUsd:1}})),
    appliedFinanceVersions:{} as FinanceSettingVersions,financeSettingVersions:vi.fn<()=>FinanceSettingVersions>(()=>({})),changedFinanceSettings,
    loadQuotationSync:vi.fn(async():Promise<{purchaseVersions:Record<string,string>;logisticsRevision:string;financeVersions?:FinanceSettingVersions}>=>({purchaseVersions:{SKU:'v1'},logisticsRevision:'r1',financeVersions:{}})),
    logisticsLoadState:{value:'ready'}, productQueryBusy:{value:false},purchaseRecords:{value:[]},
    findPurchaseProduct:()=>({}),purchaseRevision:()=> 'v1',logisticsRevision:{value:'r1'},
    savedQuoteRows:{value:[{}]},products:{value:[{logisticsAttribute:'普货'}]},buildQuoteOptions:()=>({}),
    checkSelectedLogistics:vi.fn(async()=>({revision:'r1'})),ApiError,syncPending:{value:''},syncError:{value:''}}
  const node=ast.statements.find(n=>ts.isFunctionDeclaration(n)&&n.name?.text==='checkLiveVersions')!
  const js=ts.transpile(node.getText(ast),{target:ts.ScriptTarget.ES2022})
  const run=new Function('state',`with(state){${js};return checkLiveVersions}`)(state)
  return {state,run}
}
it('accepts unchanged purchases and selected channel validation',async()=>{
  const {state,run}=setup();await run(undefined,true);expect(state.syncPending.value).toBe('')
})
it('requires refreshing a repaired purchase before an employee can save the old quote',async()=>{
  const {state,run}=setup()
  state.loadQuotationSync.mockResolvedValue({purchaseVersions:{SKU:'v2'},logisticsRevision:'r1',financeVersions:{}})
  expect(await run()).toBe(false)
  expect(state.syncPending.value).toBe('采购资料')
  expect(await run(undefined,true)).toBe(false)
  state.purchaseRevision=()=> 'v2'
  expect(await run(undefined,true)).toBe(true)
  expect(state.syncPending.value).toBe('')
})
it.each([
  [422,'当前重量没有可用运价'],[422,'渠道不在该国家及货物属性的财务允许范围内'],
  [409,'物流费用与服务器核算不一致，请重新计价'],[409,'当前渠道时效已变化，请更新报价'],
])('diagnoses generic update wording for %s %s',async(status,message)=>{
  const {state,run}=setup();state.checkSelectedLogistics.mockRejectedValue(new ApiError(message as string,status as number,'ERROR','test'))
  await run(undefined,true);expect(state.syncPending.value).toBe('已选渠道的适用价格或可用性')
})
it('does not classify a network outage as a price change',async()=>{
  const {state,run}=setup();state.checkSelectedLogistics.mockRejectedValue(new Error('offline'))
  await expect(run(undefined,true)).rejects.toThrow('offline');expect(state.syncPending.value).toBe('')
})
it('discards old SKU validation results after switching SKU',async()=>{
  const {state,run}=setup();state.checkSelectedLogistics.mockImplementation(async()=>{
    state.draftSignature=()=> 'B';throw new ApiError('old SKU failure',422,'ERROR','test')})
  await run(undefined,true);expect(state.syncPending.value).toBe('')
})
it('discards aborted validation responses',async()=>{
  const {state,run}=setup();const controller=new AbortController()
  state.checkSelectedLogistics.mockImplementation(async()=>{controller.abort();throw new ApiError('late',409,'ERROR','test')})
  await run(controller.signal,true);expect(state.syncPending.value).toBe('')
})
it('acceptance: a late failure must not invalidate a newer successful check',async()=>{
  const {state,run}=setup();let reject!:(e:Error)=>void
  state.checkSelectedLogistics.mockImplementationOnce(()=>new Promise((_,no)=>{reject=no}))
  const old=run(undefined,true);await vi.waitFor(()=>expect(state.checkSelectedLogistics).toHaveBeenCalledOnce())
  await run(undefined,true);reject(new ApiError('older failure',409,'ERROR','test'));await old
  expect(state.syncPending.value).toBe('')
})
it('keeps a newer rejection when an older successful check finishes late',async()=>{
  const {state,run}=setup();let resolve!:(value:{revision:string})=>void
  state.checkSelectedLogistics.mockImplementationOnce(()=>new Promise(done=>{resolve=done}))
  const old=run(undefined,true);await vi.waitFor(()=>expect(state.checkSelectedLogistics).toHaveBeenCalledOnce())
  state.checkSelectedLogistics.mockRejectedValueOnce(new ApiError('current failure',422,'ERROR','test'))
  await run(undefined,true);resolve({revision:'r1'});expect(await old).toBe(false)
  expect(state.syncPending.value).toBe('已选渠道的适用价格或可用性')
})
it('ignores obsolete network failures but surfaces the current failure',async()=>{
  const {state,run}=setup();let reject!:(error:Error)=>void
  state.loadQuotationSync.mockImplementationOnce(()=>new Promise((_,no)=>{reject=no}))
  const old=run(undefined,true);await run(undefined,true)
  reject(new Error('old network failure'));expect(await old).toBe(false)
  state.loadQuotationSync.mockRejectedValueOnce(new Error('current network failure'))
  await expect(run(undefined,true)).rejects.toThrow('current network failure')
})
function backgroundSetup(){
  const {state,run}=setup()
  const background=Object.assign(state,{
    buildQuoteLogisticsCountryQuery:()=>['美国'],financeCountrySettings:{value:[]},loadedQuoteCountries:{value:[]},requestedQuoteCountries:new Set(),specifiedQuoteRows:{value:[]},templateQuoteRows:{value:[]},
    loadPublishedLogisticsRules:vi.fn(async()=>({verified:true,revision:'r2',rules:[{}]})),quoteLogisticsBusy:()=>false,replaceLogisticsRules:vi.fn(),financePolicies:{value:[]},loadFinanceChannelPolicies:()=>[],logisticsRulesGeneration:{value:0}
  })
  state.loadQuotationSync.mockResolvedValue({purchaseVersions:{SKU:'v1'},logisticsRevision:'r2'})
  state.checkSelectedLogistics.mockResolvedValue({revision:'r2'})
  return {state:background,run}
}
it('does not apply an obsolete background rules response after a newer save check',async()=>{
  const {state,run}=backgroundSetup();let done!:(value:{verified:boolean;revision:string;rules:object[]})=>void
  state.loadPublishedLogisticsRules.mockImplementationOnce(()=>new Promise(resolve=>{done=resolve}))
  const old=run();await vi.waitFor(()=>expect(state.loadPublishedLogisticsRules).toHaveBeenCalledOnce())
  await run(undefined,true);done({verified:true,revision:'r2',rules:[{}]});expect(await old).toBe(false)
  expect(state.replaceLogisticsRules).not.toHaveBeenCalled();expect(state.syncPending.value).toBe('')
})
it('ignores a stale background channel rejection after a newer save check succeeds',async()=>{
  const {state,run}=backgroundSetup();let reject!:(error:Error)=>void
  state.checkSelectedLogistics.mockResolvedValueOnce({revision:'r2'}).mockImplementationOnce(()=>new Promise((_,no)=>{reject=no}))
  const old=run();await vi.waitFor(()=>expect(state.checkSelectedLogistics).toHaveBeenCalledTimes(2))
  await run(undefined,true);reject(new ApiError('stale background',409,'ERROR','test'));expect(await old).toBe(false)
  expect(state.syncPending.value).toBe('');expect(state.replaceLogisticsRules).not.toHaveBeenCalled()
})
it.each([
  ['country-classification','国家分类'],['channel-policies','物流渠道权限'],['customer-grades','客户等级系数'],
  ['exchange-rate','汇率'],['tax-settings','税费'],['surcharge-settings','附加费'],['customer-operation-fees','客户操作费'],
])('detects changed %s in the background and before saving',async(key,label)=>{
  const {state,run}=setup()
  state.appliedFinanceVersions = {[key!]:1}
  state.loadQuotationSync.mockResolvedValue({purchaseVersions:{SKU:'v1'},logisticsRevision:'r1',financeVersions:{[key!]:2}})
  expect(await run()).toBe(false)
  expect(state.syncPending.value).toBe(label)
  expect(state.hydrateFinanceSettings).not.toHaveBeenCalled()
  state.financeSettingVersions.mockReturnValue({[key!]:2})
  expect(await run(undefined,true)).toBe(false)
  expect(state.syncPending.value).toBe(label)
  expect(state.hydrateFinanceSettings).toHaveBeenCalledOnce()
  // Only applying the refreshed configuration makes the current quote up to date.
  state.appliedFinanceVersions = {[key!]:2}
  expect(await run(undefined,true)).toBe(true)
  expect(state.syncPending.value).toBe('')
})
it('refreshes finance before saving even if the lightweight snapshot is unchanged',async()=>{
  const {state,run}=setup()
  state.financeSettingVersions.mockReturnValue({'exchange-rate':2})
  expect(await run(undefined,true)).toBe(false)
  expect(state.syncPending.value).toBe('汇率')
})
it('does not accept an older in-flight finance response over a newer server version',async()=>{
  const {state,run}=setup()
  state.appliedFinanceVersions={'customer-grades':1}
  state.financeSettingVersions.mockReturnValue({'customer-grades':1})
  state.loadQuotationSync.mockResolvedValue({purchaseVersions:{SKU:'v1'},logisticsRevision:'r1',financeVersions:{'customer-grades':2}})
  expect(await run(undefined,true)).toBe(false)
  expect(state.syncPending.value).toBe('客户等级系数')
})
it('checks finance on older servers without version metadata and fails closed when offline',async()=>{
  const {state,run}=setup()
  state.loadQuotationSync.mockResolvedValue({purchaseVersions:{SKU:'v1'},logisticsRevision:'r1'})
  state.financeSettingVersions.mockReturnValue({'tax-settings':1})
  expect(await run()).toBe(false)
  expect(state.syncPending.value).toBe('税费')
  state.hydrateFinanceSettings.mockRejectedValueOnce(new Error('offline'))
  await expect(run(undefined,true)).rejects.toThrow('offline')
})
