// @vitest-environment happy-dom
import {createApp,defineComponent,h,nextTick,ref} from 'vue'
import {expect,it} from 'vitest'
import Boundary from './LogisticsPriceDisplayBoundary.vue'

it('contains a chart render failure, keeps other controls working, and supports manual recovery',async()=>{
 const broken=ref(true),clicks=ref(0),returned=ref(false)
 const Chart=defineComponent({setup:()=>()=>{if(broken.value)throw new Error('broken trend data');return h('p','trend recovered')}})
 const app=createApp({setup:()=>()=>h('div',[
   h('button',{id:'ordinary',onClick:()=>clicks.value++},String(clicks.value)),
   h(Boundary,{onWorkspace:()=>returned.value=true},{default:()=>h(Chart)}),
 ])})
 const host=document.createElement('div');document.body.append(host)
 try{
  app.mount(host);await nextTick();expect(host.textContent).toContain('价格展示发生异常')
  host.querySelector<HTMLButtonElement>('#ordinary')!.click();await nextTick();expect(clicks.value).toBe(1)
  Array.from(host.querySelectorAll('button')).find(b=>b.textContent==='返回运费规则列表')!.click();expect(returned.value).toBe(true)
  broken.value=false
  Array.from(host.querySelectorAll('button')).find(b=>b.textContent==='重新打开展示')!.click();await nextTick()
  expect(host.textContent).toContain('trend recovered')
 }finally{app.unmount();host.remove()}
})
