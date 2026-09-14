// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import Status from './LogisticsChannelStatus.vue'
import type { Channel } from '@/data/logisticsRebuild'
const update = vi.hoisted(() => vi.fn())
vi.mock('@/data/logisticsRepository', () => ({ setLogisticsChannelStatus: update }))
let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.resetAllMocks() })
const tick = async () => { await nextTick(); await nextTick() }
function setup(enabled = true, extra: Partial<Channel> = {}) {
  const changed = vi.fn(), refresh = vi.fn(), pending = vi.fn()
  const state = reactive({channel: {id:'channel-1',name:'测试渠道',enabled,_version:7,currentVersionId:'v1',...extra} as Channel,disabled:false,
    onChanged:changed,onRefresh:refresh,onPending:pending})
  const root = document.createElement('div'); document.body.append(root)
  app = createApp({render:()=>h(Status,state)}); app.mount(root)
  return {state,changed,refresh,pending}
}
function button(text: string) { return [...document.querySelectorAll('button')].find(b=>b.textContent===text)! }
it.each([true,false])('confirms status transition from enabled=%s and prevents duplicate requests',async enabled=>{
  const {changed,pending}=setup(enabled)
  let finish!: (result: unknown)=>void
  update.mockReturnValue(new Promise(done=>{finish=done}))
  button(enabled?'禁用':'启用').click();await tick()
  expect(update).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('历史')
  const confirm=button(enabled?'确认禁用':'确认启用');confirm.click();confirm.click();await tick()
  expect(update).toHaveBeenCalledExactlyOnceWith({id:'channel-1',_version:7,currentVersionId:'v1'},!enabled)
  expect(changed).not.toHaveBeenCalled()
  finish({id:'channel-1',enabled:!enabled,_version:8});await tick()
  expect(changed).toHaveBeenCalledWith({id:'channel-1',enabled:!enabled,_version:8})
  expect(pending.mock.calls).toEqual([[true],[false]])
})
it('cancel and archived channels never write',async()=>{
  const {state}=setup();button('禁用').click();await tick();button('取消').click();await tick()
  expect(update).not.toHaveBeenCalled()
  state.channel.archived=true;await tick();expect(button('禁用').disabled).toBe(true)
})
it('conflict leaves the displayed state intact, reports failure and requests fresh data',async()=>{
  const {changed,refresh}=setup();update.mockRejectedValue(new Error('物流渠道已被其他用户修改，请刷新后重试'))
  button('禁用').click();await tick();button('确认禁用').click();await tick()
  expect(changed).not.toHaveBeenCalled();expect(refresh).toHaveBeenCalledOnce()
  expect(document.body.textContent).toContain('已启用');expect(document.body.textContent).toContain('其他用户修改')
})
it('a changed channel version closes a pending confirmation instead of editing a new snapshot',async()=>{
  const {state}=setup();button('禁用').click();await tick();state.channel._version=8;await tick()
  expect(document.querySelector('[role=dialog]')).toBeNull();expect(update).not.toHaveBeenCalled()
})
it('refuses incomplete version data rather than bypassing optimistic locking',async()=>{
  setup(true,{_version:undefined});button('禁用').click();await tick();button('确认禁用').click();await tick()
  expect(update).not.toHaveBeenCalled();expect(document.body.textContent).toContain('资料不完整')
})
