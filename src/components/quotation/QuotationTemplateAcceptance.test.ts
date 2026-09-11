// @vitest-environment happy-dom
import {afterEach, expect, it, vi} from 'vitest'
import {createApp,h,nextTick,reactive,type App} from 'vue'
import Matrix from './QuotationMatrix.vue'
import CommonMatrix from './QuotationCommonMatrix.vue'
import type {QuotationMatrixRow} from './types'
let app:App
const country={name:'美国',code:'US',lowestQuote:10,grouped:false,continent:'北美洲' as const,stage:'common' as const,sortOrder:1,channelCount:2}
function row(id:string):QuotationMatrixRow{return {country:'美国',channelKey:id,rule:id,carrier:'物流',transport:'渠道'+id,quoteRegion:'全国统一',quote1:10,quote2:20,quote3:null,quoteCustom:null,eta:'5天',taxConfigured:true} as QuotationMatrixRow}
const tick=async()=>{await nextTick();await nextTick();await nextTick()}
function button(text:string){return [...document.querySelectorAll('button')].find(b=>b.textContent?.includes(text))!}
function setup(){const changed=vi.fn();const state=reactive({active:true,adoptedCountry:'',adoptedRule:'',adoptedCarrier:'',variant:'template' as const,countries:[country],contextKey:'a',customQuantity:5,exchangeRate:7,presetVersion:1,presetSelection:[{...row('missing')}],quoteRowsForCountry:()=>[row('ok')],unavailableReason:():string=> '超过重量上限',onSelectionChange:changed});const host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(Matrix,state)});app.mount(host);return{state,changed}}
afterEach(()=>{app?.unmount();document.body.innerHTML=''})
it.each([true,false])('common mode synchronizes its current selection on return, eligible=%s',async eligible=>{
  const changed=vi.fn();const state=reactive({active:true,countries:[country],contextKey:'a',adoptedCountry:'',adoptedRule:'',adoptedCarrier:'',exchangeRate:7,customQuantity:5,presetSelection:[row('ok')],presetVersion:1,quoteRowsForCountry:()=>[row('ok')],onSelectionChange:changed});
  const host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(CommonMatrix,state)});app.mount(host);await tick();
  expect(changed.mock.lastCall?.[0][0].quote1).toBe(10);
  state.active=false;await tick();state.quoteRowsForCountry=()=>eligible?[{...row('ok'),quote1:23}]:[];state.contextKey='b';await tick();state.active=true;await tick();
  expect(changed.mock.lastCall?.[0]).toEqual(eligible?[expect.objectContaining({quote1:23})]:[])
})
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
it('rejects a partially invalid batch replacement without removing the original',async()=>{
  const {state,changed}=setup();state.quoteRowsForCountry=()=>[row('ok'),row('second')];await tick();button('替换渠道').click();await tick();
  document.querySelectorAll<HTMLInputElement>('.picker-list input').forEach(input=>input.click());await tick();
  state.quoteRowsForCountry=()=>[row('ok')];state.contextKey='changed';await tick();button('批量添加渠道').click();await tick();
  expect(changed.mock.lastCall?.[0].map((r:QuotationMatrixRow)=>r.channelKey)).toEqual(['missing'])
})
it('cancelling a loading replacement does not turn the next ordinary add into a replacement',async()=>{
  const {state,changed}=setup();await tick();let resolve!:(ok:boolean)=>void;
  Object.assign(state,{ensureCountries:vi.fn().mockImplementationOnce(()=>new Promise<boolean>(done=>{resolve=done})).mockResolvedValue(true)});await tick();
  button('替换渠道').click();await tick();button('取消').click();await tick();resolve(true);await tick();
  button('添加渠道').click();await tick();(document.querySelector('.picker-list input') as HTMLInputElement).click();await tick();button('批量添加渠道').click();await tick();
  expect(changed.mock.lastCall?.[0].map((r:QuotationMatrixRow)=>r.channelKey).sort()).toEqual(['missing','ok'])
})
it('an old picker failure must not overwrite a reopened same-country picker success',async()=>{
  const {state}=setup();await tick();let resolve!:(ok:boolean)=>void;
  Object.assign(state,{ensureCountries:vi.fn().mockImplementationOnce(()=>new Promise<boolean>(done=>{resolve=done})).mockResolvedValue(true)});await tick();
  button('替换渠道').click();await tick();button('取消').click();await tick();button('添加渠道').click();await tick();
  expect(document.body.textContent).not.toContain('加载失败，点击重试');resolve(false);await tick();
  expect(document.body.textContent).not.toContain('加载失败，点击重试')
  ;(document.querySelector('.picker-list input') as HTMLInputElement).click();await tick();button('批量添加渠道').click();await tick();
  expect(document.querySelector('.picker-list')).toBeNull()
})
it('does not duplicate a restored channel when adding the already displayed national row',async()=>{
  const{state,changed}=setup();state.presetSelection=[{...row('ok'),quoteRegion:'非偏远'}];state.quoteRowsForCountry=()=>[];state.presetVersion++;await tick();
  state.quoteRowsForCountry=()=>[row('ok')];state.contextKey='loaded';await tick();button('添加渠道').click();await tick();
  const input=document.querySelector('.picker-list input') as HTMLInputElement
  if (!input.disabled) {input.click();await tick();button('批量添加渠道').click();await tick()}
  expect(changed.mock.lastCall?.[0]).toHaveLength(1)
})
it('deduplicates previously saved aliases after recovery and removes every alias together',async()=>{
  const {state,changed}=setup();state.presetSelection=[row('ok'),{...row('ok'),quoteRegion:'非偏远'}];state.quoteRowsForCountry=()=>[];state.presetVersion++;await tick();
  expect(changed.mock.lastCall?.[0]).toHaveLength(2);state.quoteRowsForCountry=()=>[row('ok')];state.contextKey='recovered';await tick();
  expect(changed.mock.lastCall?.[0]).toHaveLength(1);button('移出报价单').click();await tick();expect(changed.mock.lastCall?.[0]).toEqual([])
})
it('rechecks duplicates when a pending replacement becomes the recovered original row',async()=>{
  const {state,changed}=setup();state.presetSelection=[{...row('ok'),quoteRegion:'非偏远'}];state.quoteRowsForCountry=()=>[row('ok'),{...row('ok'),quoteRegion:'2区'}];state.presetVersion++;await tick();
  button('替换渠道').click();await tick();(document.querySelector('.picker-list input') as HTMLInputElement).click();await tick();
  state.quoteRowsForCountry=()=>[row('ok')];state.contextKey='recovered';await tick();button('批量添加渠道').click();await tick();
  expect(changed.mock.lastCall?.[0]).toHaveLength(1);expect(changed.mock.lastCall?.[0][0].quote1).toBe(10)
})
it('keeps a newer picker failure when an older successful request arrives',async()=>{
  const {state}=setup();await tick();let resolve!:(ok:boolean)=>void;
  Object.assign(state,{ensureCountries:vi.fn().mockImplementationOnce(()=>new Promise<boolean>(done=>{resolve=done})).mockResolvedValue(false)});await tick();
  button('替换渠道').click();await tick();button('取消').click();await tick();button('添加渠道').click();await tick();resolve(true);await tick();
  expect(document.body.textContent).toContain('加载失败，点击重试')
})
it('publishes a changed channel identity even when labels and prices match',async()=>{
  const {state,changed}=setup();const first={...row('first'),rule:'同名规则',transport:'同名渠道'};const second={...first,channelKey:'second'};
  state.quoteRowsForCountry=()=>[first,second];state.presetSelection=[first];state.presetVersion++;await tick();
  expect(changed.mock.lastCall?.[0][0].channelKey).toBe('first');state.presetSelection=[second];state.presetVersion++;await tick();
  expect(changed.mock.lastCall?.[0][0].channelKey).toBe('second')
})
it('does not resurrect removed aliases after another weight change',async()=>{
  const {state,changed}=setup();state.presetSelection=[row('ok'),{...row('ok'),quoteRegion:'非偏远'}];state.quoteRowsForCountry=()=>[];state.presetVersion++;await tick();
  state.quoteRowsForCountry=()=>[row('ok')];state.contextKey='light';await tick();button('移出报价单').click();await tick();
  state.quoteRowsForCountry=()=>[];state.contextKey='heavy';await tick();expect(changed.mock.lastCall?.[0]).toEqual([])
})
it('an old template completion cannot undo clearing the current selection',async()=>{
  const {state,changed}=setup();await tick();let resolve!:(ok:boolean)=>void;
  Object.assign(state,{ensureCountries:vi.fn().mockImplementationOnce(()=>new Promise<boolean>(done=>{resolve=done})).mockResolvedValue(true)});await tick();
  state.presetSelection=[row('ok')];state.presetVersion++;await tick();state.presetSelection=[];state.presetVersion++;await tick();
  resolve(true);await tick();expect(changed.mock.lastCall?.[0]).toEqual([])
})
it('publishes updated unavailability reasons without changing prices',async()=>{
  const {state,changed}=setup();await tick();state.unavailableReason=()=> '当前商品属性不支持';await tick();
  expect(changed.mock.lastCall?.[0][0]).toMatchObject({quote1:null,availabilityMessage:'当前商品属性不支持'})
})
it('keeps equal labels in distinct Australian zones separate during deduplication',async()=>{
  const {state,changed}=setup();state.countries=[{...country,name:'澳大利亚',code:'AU'}];const zones=[{...row('ok'),country:'澳大利亚',quoteRegion:'1区'},{...row('ok'),country:'澳大利亚',quoteRegion:'2区'}];
  state.quoteRowsForCountry=()=>zones;state.presetSelection=zones;state.presetVersion++;await tick();expect(changed.mock.lastCall?.[0]).toHaveLength(2)
})
it('publishes new cost metadata when rounded quote amounts remain unchanged',async()=>{
  const {state,changed}=setup();state.presetSelection=[row('ok')];state.quoteRowsForCountry=()=>[{...row('ok'),freight:5,totalCostCny:50}];state.presetVersion++;await tick();
  state.quoteRowsForCountry=()=>[{...row('ok'),freight:5.01,totalCostCny:50.01}];state.contextKey='new-cost';await tick();
  expect(changed.mock.lastCall?.[0][0]).toMatchObject({quote1:10,freight:5.01,totalCostCny:50.01})
})
it('publishes changed unavailable tier explanations without numeric tier changes',async()=>{
  const {state,changed}=setup();state.presetSelection=[row('ok')];state.quoteRowsForCountry=()=>[{...row('ok'),quantityMessages:{'3':'超过5kg'}}];state.presetVersion++;await tick();
  state.quoteRowsForCountry=()=>[{...row('ok'),quantityMessages:{'3':'超过体积限制'}}];state.contextKey='new-cause';await tick();
  expect(changed.mock.lastCall?.[0][0].quantityMessages).toEqual({'3':'超过体积限制'})
})
it('does not emit duplicate selections for equivalent recalculated rows',async()=>{
  const {state,changed}=setup();state.presetSelection=[row('ok')];state.quoteRowsForCountry=()=>[row('ok')];state.presetVersion++;await tick();
  const count=changed.mock.calls.length
  for(let i=0;i<5;i++) {state.quoteRowsForCountry=()=>[Object.fromEntries(Object.entries(row('ok')).reverse()) as QuotationMatrixRow];state.contextKey=String(i);await tick()}
  expect(changed).toHaveBeenCalledTimes(count)
})

it('resynchronizes changed prices after returning from an inactive mode',async()=>{
  const {state,changed}=setup();state.presetSelection=[row('ok')];state.presetVersion++;await tick();
  state.active=false;await tick();state.quoteRowsForCountry=()=>[{...row('ok'),quote1:99}];state.contextKey='new-sku';await tick();
  state.active=true;await tick();expect(changed.mock.lastCall?.[0][0].quote1).toBe(99)
})

it.each(['template','specified'])('resynchronizes an updated missing reason after returning to %s mode',async variant=>{
  const {state,changed}=setup();Object.assign(state,{variant});await tick();state.active=false;await tick();
  state.unavailableReason=()=> '不支持带电商品';state.contextKey='battery';await tick();
  state.active=true;await tick();expect(changed.mock.lastCall?.[0][0].availabilityMessage).toBe('不支持带电商品')
})

it('keeps the newest template when requests complete in reverse order',async()=>{
  const {state,changed}=setup();await tick();let first!:(ok:boolean)=>void;let second!:(ok:boolean)=>void;
  Object.assign(state,{ensureCountries:vi.fn().mockImplementationOnce(()=>new Promise<boolean>(done=>{first=done})).mockImplementationOnce(()=>new Promise<boolean>(done=>{second=done}))});
  state.presetSelection=[row('ok')];state.presetVersion++;await tick();
  state.presetSelection=[row('new')];state.presetVersion++;await tick();
  second(true);await tick();first(true);await tick();
  expect(changed.mock.lastCall?.[0].map((r:QuotationMatrixRow)=>r.channelKey)).toEqual(['new'])
})

it('can reapply a template after a failed load without losing the prior selection',async()=>{
  const {state,changed}=setup();await tick();
  Object.assign(state,{ensureCountries:vi.fn().mockResolvedValueOnce(false).mockResolvedValue(true)});
  state.presetSelection=[row('ok')];state.presetVersion++;await tick();
  expect(changed.mock.lastCall?.[0][0].channelKey).toBe('missing');
  state.presetVersion++;await tick();expect(changed.mock.lastCall?.[0][0].channelKey).toBe('ok')
})

it('copies the latest cost snapshot and partial quantity prices after recalculation',async()=>{
  const {state}=setup();const copied=vi.fn();Object.assign(state,{onCopy:copied});
  state.presetSelection=[row('ok')];state.presetVersion++;await tick();
  state.quoteRowsForCountry=()=>[{...row('ok'),freight:7.02,totalCostCny:61.02,quantityMessages:{'3':'超过重量'}}];state.contextKey='recalculated';await tick();
  button('复制表格数据').click();await tick();
  expect(copied.mock.lastCall?.[0][0]).toMatchObject({freight:7.02,totalCostCny:61.02,quote3:null,quantityMessages:{'3':'超过重量'}})
})

it.each(['template','specified'])('clears previously valid amounts when a channel fails while %s mode is inactive',async variant=>{
  const {state,changed}=setup();Object.assign(state,{variant});state.presetSelection=[row('ok')];state.presetVersion++;await tick();
  expect(changed.mock.lastCall?.[0][0].quote1).toBe(10);
  state.active=false;await tick();state.quoteRowsForCountry=()=>[];state.contextKey='overweight';await tick();
  state.active=true;await tick();
  expect(document.querySelector('.selected-channels')?.textContent).toContain('超过重量上限');
  expect(changed.mock.lastCall?.[0][0]).toMatchObject({available:false,quote1:null,quote2:null})
})

it('preserves the latest state through 40 alternating eligible and overweight recalculations',async()=>{
  const {state,changed}=setup();state.presetSelection=[row('ok')];state.presetVersion++;await tick();
  for(let i=0;i<40;i++) {
    state.quoteRowsForCountry=()=>i%2 ? [] : [{...row('ok'),quote1:10+i}];state.contextKey=`sku-${i}`;await tick();
    expect(changed.mock.lastCall?.[0]).toHaveLength(1);
    expect(changed.mock.lastCall?.[0][0].quote1).toBe(i%2 ? null : 10+i)
  }
})
