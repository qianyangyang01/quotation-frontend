// @vitest-environment happy-dom
import { expect, it, vi } from 'vitest'
const { post, get } = vi.hoisted(() => ({ post: vi.fn(), get: vi.fn() }))
vi.mock('@/services/http', () => ({ api: { post, get }, idempotencyKey: () => 'test' }))
import { createQuotationTemplate, loadQuotationTemplates } from './quotationTemplates'
import { draftSelection } from '@/services/quotationDrafts'
it('preserves regional variants through template write and reload while deduplicating exact repeats', async () => {
  const items = [3, 4, 3].map(n => ({ country: '澳大利亚', countryCode: 'AU', quoteRegion: `澳大利亚${n}区`,
    channelKey: 'same-channel', ruleId: 1, rule: '规则', carrier: '物流商', transport: '渠道', channelCode: 'C1' }))
  post.mockImplementation(async (_url, body) => ({ ...body, id: 'template-1', createdAt: '2026-09-08', updatedAt: '2026-09-08' }))
  const saved = await createQuotationTemplate({ account: 'QA' }, { name: '澳洲两区', items })
  expect(saved.items.map(i => i.quoteRegion)).toEqual(['澳大利亚3区', '澳大利亚4区'])
  get.mockResolvedValue([saved])
  expect((await loadQuotationTemplates({ account: 'QA' }))[0].items).toEqual(saved.items)
  expect(draftSelection(saved.items).map(i => i.quoteRegion)).toEqual(['澳大利亚3区', '澳大利亚4区'])
})
