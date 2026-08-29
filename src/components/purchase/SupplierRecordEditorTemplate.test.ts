import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const template = readFileSync(new URL('./SupplierRecordEditor.vue', import.meta.url), 'utf8')

describe('supplier record editor select prompts', () => {
  it('uses the requested prompt for all seven nullable selects', () => {
    expect(template.match(/<option[^>]*>请选择<\/option>/g)).toHaveLength(7)
    expect(template).not.toMatch(/<option[^>]*>未填写<\/option>/)
  })

  it('keeps pending evaluation as a business status', () => {
    expect(template).toContain('<label>评级<select v-model="model.rating"><option>待评价</option>')
  })
})
