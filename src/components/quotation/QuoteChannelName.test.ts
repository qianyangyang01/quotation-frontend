// @vitest-environment happy-dom
import { createApp, h } from 'vue'
import { expect, it } from 'vitest'
import QuoteChannelName from './QuoteChannelName.vue'

it('shows delivery badges beside coverage without inferring them from the channel name', () => {
  for (const deliveryModes of [undefined, ['自提'], ['到门'], ['自提', '到门']] as const) {
    const host = document.createElement('div')
    const app = createApp({ render: () => h(QuoteChannelName, { row: { country: '以色列', carrier: '物流商', transport: '渠道', rule: '规则', quoteRegion: '全国统一', deliveryModes: deliveryModes ? [...deliveryModes] : undefined } }) })
    app.mount(host)
    try {
      expect([...host.querySelectorAll('.channel-title-line .delivery-badge')].map(node => node.textContent)).toEqual(deliveryModes || [])
      expect(host.querySelector('.channel-title')?.textContent).toBe('物流商｜渠道')
    } finally { app.unmount() }
  }
})
