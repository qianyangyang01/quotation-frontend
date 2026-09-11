// @vitest-environment happy-dom
import {afterEach,expect,it,vi} from 'vitest'
import {createApp,h,nextTick,reactive,type App} from 'vue'
import Template from './QuotationTemplateMatrix.vue'
import type {QuotationMatrixRow} from './types'
vi.mock('@/data/quotationTemplates',async(importOriginal)=>{
  const original=await importOriginal<typeof import('@/data/quotationTemplates')>()
  return {...original,loadQuotationTemplates:vi.fn(async()=>[{
    id:'template-1',name:'验收模板',ownerKey:'TEST',ownerAccount:'TEST',ownerName:'测试',createdAt:'2026-09-11',updatedAt:'2026-09-11',
    items:[{country:'美国',countryCode:'US',quoteRegion:'全国统一',channelKey:'1',ruleId:1,rule:'规则',carrier:'物流',transport:'渠道',channelCode:'C1'}]
  }])}
})
let app:App
const tick=async()=>{await nextTick();await nextTick();await nextTick()}
afterEach(()=>{app?.unmount();document.body.innerHTML=''})
it.each([false,true])('updates the template availability summary after a weight change, hidden=%s',async hidden=>{
  const pricing=reactive({available:true,active:true})
  const props={get active(){return pricing.active},countries:[{name:'美国',code:'US',channelCount:1,lowestQuote:10,grouped:false,stage:'common' as const,continent:'北美洲' as const,sortOrder:1}],contextKey:'test',customQuantity:5,adoptedCountry:'',adoptedRule:'',adoptedCarrier:'',exchangeRate:7,ownerName:'测试',ownerAccount:'TEST',
    unavailableReason:()=> '超过5kg上限',quoteRowsForCountry:()=>pricing.available?[{country:'美国',channelKey:'1',rule:'规则',carrier:'物流',transport:'渠道',quoteRegion:'全国统一',quote1:10,quote2:20,quote3:null,quoteCustom:null,taxConfigured:true,eta:'5天'} as QuotationMatrixRow]:[]}
  const host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(Template,props)});app.mount(host);await tick()
  const apply=[...document.querySelectorAll('button')].find(b=>b.textContent?.includes('一键应用'))!;expect(apply.disabled).toBe(false);apply.click();await tick()
  expect(document.querySelector('.template-status')?.textContent).toContain('1 条模板渠道可用')
  if(hidden){pricing.active=false;await tick()}
  pricing.available=false;await tick();pricing.active=true;await tick();expect(document.querySelector('.selected-channels')?.textContent).toContain('超过5kg上限')
  expect(document.querySelector('.template-status')?.textContent).not.toContain('1 条模板渠道可用')
  expect(document.querySelector('.template-status')?.textContent).toContain('不可用')
  pricing.available=true;await tick()
  expect(document.querySelector('.template-status')?.textContent).toContain('1 条模板渠道可用')
  expect(document.querySelector('.template-status')?.textContent).not.toContain('不可用')
  ;[...document.querySelectorAll('button')].find(b=>b.textContent?.includes('移出报价单'))!.click();await tick()
  expect(document.querySelector('.template-status')?.textContent).toContain('0 条渠道')
  expect(document.querySelector('.active-template')?.textContent).toContain('1 条渠道')
  ;[...document.querySelectorAll('button')].find(b=>b.textContent?.includes('恢复模板已保存清单'))!.click();await tick()
  expect(document.querySelector('.template-status')?.textContent).toContain('1 条模板渠道可用')
})
