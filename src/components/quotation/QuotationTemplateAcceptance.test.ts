// @vitest-environment happy-dom
import {afterEach, expect, it, vi} from 'vitest'
import {createApp,h,nextTick,reactive,type App} from 'vue'
import Matrix from './QuotationMatrix.vue'
import type {QuotationMatrixRow} from './types'
let app:App
const country={name:'美国',code:'US',lowestQuote:10,grouped:false,continent:'北美洲' as const,stage:'common' as const,sortOrder:1,channelCount:2}
function row(id:string):QuotationMatrixRow{return {country:'美国',channelKey:id,rule:id,carrier:'物流',transport:'渠道'+id,quoteRegion:'全国统一',quote1:10,quote2:20,quote3:null,quoteCustom:null,eta:'5天',taxConfigured:true} as QuotationMatrixRow}
const tick=async()=>{await nextTick();await nextTick();await nextTick()}
function button(text:string){return [...document.querySelectorAll('button')].find(b=>b.textContent?.includes(text))!}
function setup(){const changed=vi.fn();const state=reactive({active:true,adoptedCountry:'',adoptedRule:'',adoptedCarrier:'',variant:'template' as const,countries:[country],contextKey:'a',customQuantity:5,exchangeRate:7,presetVersion:1,presetSelection:[{...row('missing')}],quoteRowsForCountry:()=>[row('ok')],unavailableReason:()=> '超过重量上限',onSelectionChange:changed});const host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(Matrix,state)});app.mount(host);return{state,changed}}
afterEach(()=>{app?.unmount();document.body.innerHTML=''})
it('keeps unavailable identity, null amounts and disabled primary',async()=>{const{changed}=setup();await tick();expect(changed.mock.lastCall?.[0]).toEqual([expect.objectContaining({channelKey:'missing',quote1:null,quote2:null,quote3:null,quoteCustom:null})]);expect(button('设为首选').disabled).toBe(true)})
it('cancel replacement keeps original template selection',async()=>{const{state,changed}=setup();await tick();button('替换渠道').click();await tick();button('取消').click();await tick();expect(changed.mock.lastCall?.[0][0].channelKey).toBe('missing');expect(state.presetSelection[0]?.channelKey).toBe('missing')})
it('confirmed replacement preserves template and adds a current eligible channel',async()=>{const{state,changed}=setup();await tick();button('替换渠道').click();await tick();(document.querySelector('.picker-list input') as HTMLInputElement).click();await tick();button('批量添加渠道').click();await tick();expect(changed.mock.lastCall?.[0].map((r:QuotationMatrixRow)=>r.channelKey)).toEqual(['ok']);expect(state.presetSelection[0]?.channelKey).toBe('missing')})
it('restores placeholder from a serialized draft without inventing prices',async()=>{const{state,changed}=setup();await tick();state.presetSelection=JSON.parse(JSON.stringify(changed.mock.lastCall?.[0]));state.presetVersion++;await tick();expect(changed.mock.lastCall?.[0][0]).toMatchObject({channelKey:'missing',quote1:null,available:false})})
it('does not lose both rows when a selected replacement becomes unavailable before confirmation',async()=>{const{state,changed}=setup();await tick();button('替换渠道').click();await tick();(document.querySelector('.picker-list input') as HTMLInputElement).click();await tick();state.quoteRowsForCountry=()=>[];state.contextKey='b';await tick();button('批量添加渠道').click();await tick();expect(changed.mock.lastCall?.[0].map((r:QuotationMatrixRow)=>r.channelKey)).toEqual(['missing']);expect(document.body.textContent).toContain('原方案已保留')})
it('can remove a restored channel after its old zone maps to a national price',async()=>{const{state,changed}=setup();state.presetSelection=[row('ok')];state.quoteRowsForCountry=()=>[];state.presetVersion++;await tick();state.presetSelection[0]!.quoteRegion='非偏远';state.presetVersion++;await tick();state.quoteRowsForCountry=()=>[row('ok')];state.contextKey='loaded';await tick();expect(changed.mock.lastCall?.[0][0].quote1).toBe(10);button('移出报价单').click();await tick();expect(changed.mock.lastCall?.[0]).toEqual([])})
it('preserves independent countries and partial quantity prices in a mixed template',async()=>{
  const {state,changed}=setup();
  state.countries.push({...country,name:'加拿大',code:'CA'});
  state.presetSelection=[row('ok'),{...row('missing'),country:'加拿大'}];state.presetVersion++;
  state.quoteRowsForCountry=((name:string)=>name==='美国'?[row('ok')]:[]) as typeof state.quoteRowsForCountry;
  await tick();expect(changed.mock.lastCall?.[0]).toHaveLength(2);
  expect(changed.mock.lastCall?.[0][0]).toMatchObject({quote1:10,quote2:20,quote3:null,quoteCustom:null});
  expect(changed.mock.lastCall?.[0][1]).toMatchObject({country:'加拿大',available:false,quote1:null});
  state.active=false;await tick();state.active=true;await tick();expect(changed.mock.lastCall?.[0]).toHaveLength(2)
})