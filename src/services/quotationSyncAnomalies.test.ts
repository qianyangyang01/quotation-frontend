import {readFileSync} from 'node:fs'
import ts from 'typescript'
import {expect,it,vi} from 'vitest'
import {ApiError} from './http'
const source=readFileSync(new URL('../views/QuotationSystemView.vue',import.meta.url),'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
const ast=ts.createSourceFile('view.ts',source,ts.ScriptTarget.Latest,true)
function setup(){
  const state={liveVersionCheckSequence:0,activePurchaseSkus:()=>['SKU'],draftSignature:()=> 'A',
    selectedCustomerId:{value:''},customerName:{value:'甲'},customerOperation:{value:{snapshot:{id:'a',name:'甲',feeUsd:1}}},
    hydrateFinanceSettings:vi.fn(async()=>{}),loadCustomerOperationSettings:()=>({}),resolveCustomerOperation:vi.fn(()=>({configured:true,snapshot:{id:'a',name:'甲',feeUsd:1}})),
    loadQuotationSync:vi.fn(async()=>({purchaseVersions:{SKU:'v1'},logisticsRevision:'r1'})),
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
it('blocks stale finance customer fees before save and keeps manual clients independent',async()=>{
  const {state,run}=setup();state.selectedCustomerId.value='a'
  state.resolveCustomerOperation.mockReturnValue({configured:true,snapshot:{id:'a',name:'甲',feeUsd:2}})
  await run(undefined,true);expect(state.syncPending.value).toBe('客户操作费')
  expect(state.hydrateFinanceSettings).toHaveBeenCalledOnce()
  state.selectedCustomerId.value='';await run(undefined,true);expect(state.syncPending.value).toBe('')
})
