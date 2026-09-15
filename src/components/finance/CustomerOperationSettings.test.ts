// @vitest-environment happy-dom
import { createApp, h, nextTick } from 'vue'
import { expect, it, vi } from 'vitest'
const store = vi.hoisted(() => ({ value: { customers: [] as unknown[] }, write: vi.fn() }))
vi.mock('@/services/financeSettings', () => ({ readFinanceSetting: () => store.value, writeFinanceSetting: (...args: unknown[]) => store.write(...args) }))
import Settings from './CustomerOperationSettings.vue'
it('saves finance customers, blocks duplicate clicks, preserves edits after conflicts and restores persisted rows', async () => {
  const host = document.createElement('div'); document.body.append(host)
  let app = createApp({ render: () => h(Settings) }); app.mount(host)
  const settle = async () => { for(let i=0;i<5;i++) { await Promise.resolve(); await nextTick() } }
  try {
    host.querySelector<HTMLButtonElement>('.add')!.click(); await nextTick()
    const name = host.querySelector<HTMLInputElement>('[aria-label=客户名称1]')!, fee = host.querySelector<HTMLInputElement>('[aria-label=操作费1]')!
    name.value = '客户甲'; name.dispatchEvent(new Event('input')); fee.value = '1.25'; fee.dispatchEvent(new Event('input')); await nextTick()
    let complete!: (value: unknown) => void
    store.write.mockImplementation((_key, value) => new Promise(resolve => { complete = () => { store.value = value; resolve(value) } }))
    const save = host.querySelector<HTMLButtonElement>('header button')!; save.click(); save.click(); await settle()
    expect(store.write).toHaveBeenCalledTimes(1); expect(save.disabled).toBe(true)
    complete(store.value); await settle(); expect(host.textContent).toContain('已保存')
    app.unmount(); app=createApp({render:()=>h(Settings)}); app.mount(host); await settle()
    expect(host.querySelector<HTMLInputElement>('[aria-label=操作费1]')!.value).toBe('1.25')
    store.write.mockRejectedValueOnce(new Error('财务设置已被其他用户修改，请刷新后重试'))
    host.querySelector<HTMLButtonElement>('header button')!.click(); await settle()
    expect(host.textContent).toContain('其他用户修改'); expect(host.querySelector<HTMLInputElement>('[aria-label=客户名称1]')!.value).toBe('客户甲')
  } finally { app.unmount(); host.remove() }
})
