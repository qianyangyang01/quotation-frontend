// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { createApp, h } from 'vue'
import ChannelCoverageBadge from './ChannelCoverageBadge.vue'

function render(name: string) {
  const host = document.createElement('div')
  const app = createApp({ render: () => h(ChannelCoverageBadge, { name }) })
  app.mount(host)
  const result = { text: host.textContent, title: host.querySelector('mark')?.getAttribute('title') }
  app.unmount()
  return result
}

describe('Rongding cosmetics delivery coverage', () => {
  it.each([
    ['美国化妆品专线A(SP)', '仅支持商派可达邮编，以到货预报为准'],
    ['美国标准DDP专线-特惠化妆品A(SP)', '仅支持特惠商派可达邮编，以到货预报为准'],
    ['美国化妆品专线A（SP）', '仅支持商派可达邮编，以到货预报为准'],
  ])('shows limited commercial delivery for %s', (name, title) => {
    expect(render(name)).toEqual({ text: '商派', title })
  })
  it.each(['美国纯商派化妆品专线', '美国精品专线', ''])('does not label %s as all ZIP codes', name => {
    expect(render(name).text).not.toContain('全邮编')
  })
  it('preserves the existing ePacket and unrelated channel labels', () => {
    expect(render('TY-E邮宝').text).toBe('指定邮编')
    expect(render('美国USPS化妆品专线A').text).toBe('全邮编')
  })
})
