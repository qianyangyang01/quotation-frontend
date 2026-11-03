// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, type App } from 'vue'
import WomensPricingTrace from './WomensPricingTrace.vue'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
import { defaultWomensPricing, womensPricingSample } from '@/data/womensPricing'

const permissions=vi.hoisted(()=>({finance:false,full:false}))
vi.mock('@/data/quotationVisibility',()=>({
  canViewPricingFactors:{__v_isRef:true,get value(){return permissions.finance}},
  canViewFullPricing:{__v_isRef:true,get value(){return permissions.full}},
}))
let app:App|undefined
afterEach(()=>{app?.unmount();document.body.innerHTML='';permissions.finance=false;permissions.full=false})
function mount(mode='womens-bundle',snapshot=true,legacy=false){
  const policy={...defaultWomensPricing(),version:4}
  if(legacy){policy.schemaVersion=1;policy.basis='total-cost-usd';delete policy.referenceCoefficient}
  const record=normalizeQuotationRecord({id:'w',no:'W',quoteMode:mode as 'womens-bundle',exchangeRate:7,
    womensPricing:snapshot?policy:undefined,quoteOptions:[{id:'o',country:'美国',carrier:'测试',channel:'测试',rule:'测试',eta:'',quote1Usd:10,quote2Usd:20,quote3Usd:30,quoteCustomUsd:40,womensPricingSamples:[legacy?{quantity:2,costCny:140,fromUsd:20,toUsd:30,coefficient:1.25,baseQuoteCny:175}:womensPricingSample(policy,140,7,2)]}]})!
  const host=document.createElement('div');document.body.append(host);app=createApp(WomensPricingTrace,{record});app.mount(host);return host
}
it('shows historical policy and per-set evidence to fully authorized reviewers',()=>{
  permissions.finance=true;permissions.full=true;const host=mount()
  expect(host.textContent).toContain('规则版本 V5');expect(host.textContent).toContain('2套')
  expect(host.textContent).toContain('1.25');expect(host.textContent).toContain('175.0000')
})
it('keeps the explanation but hides factors and cost tables from sales',()=>{
  const host=mount();expect(host.textContent).toContain('规则版本 V5')
  expect(host.textContent).not.toContain('1.27');expect(host.querySelector('table')).toBeNull()
})
it('does not infer missing historical factors from the current policy',()=>{
  permissions.finance=true;permissions.full=true;const host=mount('womens-single',false)
  expect(host.textContent).toContain('不能使用当前规则推算历史系数');expect(host.querySelector('table')).toBeNull()
})
it('does not change ordinary quotation details',()=>{expect(mount('single').textContent).toBe('')})

it('labels legacy history with its original cost basis and never applies the new reference factor',()=>{
  permissions.finance=true;permissions.full=true;const host=mount('womens-single',true,true)
  expect(host.textContent).toContain('旧规则：综合成本折美元直接选档')
  expect(host.querySelector('tbody tr')?.textContent).toContain('20.000000')
  expect(host.textContent).not.toContain('24.600000')
})
it('shows the saved reference amount and factor for the new policy',()=>{
  permissions.finance=true;permissions.full=true;const host=mount()
  expect(host.textContent).toContain('选档基准系数 1.23')
  expect(host.querySelector('tbody tr')?.textContent).toContain('24.600000')
})
