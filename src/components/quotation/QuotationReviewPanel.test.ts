// @vitest-environment happy-dom
import { afterEach, expect, it } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import Panel from './QuotationReviewPanel.vue'
import { normalizeQuotationRecord, type ReviewAction } from '@/data/quotationRecords'
let app:App
afterEach(()=>{app?.unmount();document.body.innerHTML=''})
async function mount(account='F2',admin=false){
  const record=normalizeQuotationRecord({id:'a',no:'Q',_version:2,_reviewVersion:1,financeReviewStatus:'reviewing',financeReviewClaimedAccount:'F1',financeReviewClaimedBy:'财务一'})!
  const actions:ReviewAction[]=[];app=createApp(Panel,{record,state:record,account,admin,canReview:true,busy:false,onAction:(a:ReviewAction)=>actions.push(a)})
  const host=document.createElement('div');document.body.append(host);app.mount(host);await nextTick();return actions
}
it('another finance account has no cancel, complete or release control',async()=>{
  await mount();expect(document.querySelectorAll('button')).toHaveLength(0);expect(document.body.textContent).toContain('财务一审核中')
})
it('only admin may release with a nonempty reason',async()=>{
  const actions=await mount('ADMIN',true);const button=document.querySelector('button')!;expect(button.disabled).toBe(true)
  const note=document.querySelector('textarea')!;note.value='审核人休假，解除占用';note.dispatchEvent(new Event('input'));await nextTick();button.click();expect(actions).toEqual([{action:'release',note:note.value}])
})
it('owner may cancel without a note but rejection requires explanation',async()=>{
  const actions=await mount('F1');const buttons=[...document.querySelectorAll('button')];expect(buttons.find(b=>b.textContent==='审核完成 · 价格有误')?.disabled).toBe(true)
  buttons.find(b=>b.textContent==='取消审核')!.click();expect(actions).toEqual([{action:'cancel'}])
})
