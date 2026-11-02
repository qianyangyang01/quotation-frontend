// @vitest-environment happy-dom
import { createApp, defineComponent, h, nextTick, ref } from 'vue'
import { expect, it } from 'vitest'
import Boundary from './LogisticsUpdateOverviewBoundary.vue'

it('contains an overview render failure, keeps the surrounding workspace usable and allows recovery', async () => {
  let broken = true
  const outsideClicks = ref(0)
  const visible = ref(true)
  const Child = defineComponent({ render() { if (broken) throw new Error('injected overview failure'); return h('p', '恢复后的概览') } })
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp({ render: () => h('main', [
    h('button', { class: 'quotation', onClick: () => outsideClicks.value++ }, `报价 ${outsideClicks.value}`),
    visible.value ? h(Boundary, { onClose: () => { visible.value = false } }, { default: () => h(Child) }) : null,
  ]) })
  app.mount(host)
  try {
    await nextTick()
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('更新概览暂时无法显示')
    host.querySelector<HTMLButtonElement>('.quotation')!.click(); await nextTick()
    expect(host.querySelector('.quotation')!.textContent).toBe('报价 1')
    broken = false
    host.querySelector<HTMLButtonElement>('aside button')!.click(); await nextTick()
    expect(host.querySelector('[role="alert"]')).toBeNull()
    expect(host.textContent).toContain('恢复后的概览')
    expect(host.querySelector('.quotation')!.textContent).toBe('报价 1')
  } finally { app.unmount(); host.remove() }
})
