import { expect, it } from 'vitest'
import { priceRegionLabel } from './priceRegionLabel'

const row = { country: '澳大利亚', carrier: '通邮', rule: '标准挂号类-敏感', quoteRegion: '澳大利亚3区' }

it('labels an unzoned price without altering the destination used for pricing and persistence', () => {
  const source = Object.freeze({ ...row, priceRegion: '' })
  expect(priceRegionLabel(source)).toBe('全国统一价')
  expect(source.quoteRegion).toBe('澳大利亚3区')
  expect(priceRegionLabel({ ...source, quoteRegion: '澳大利亚2区' })).toBe('全国统一价')
})

it('uses the actual matched zone, including single-zone and combined-zone tariffs', () => {
  expect(priceRegionLabel({ ...row, priceRegion: '澳大利亚2区' })).toBe('澳大利亚2区')
  expect(priceRegionLabel({ ...row, priceRegion: '2区/3区' })).toBe('澳大利亚 · 3区')
  expect(priceRegionLabel({ ...row, country: '加拿大', quoteRegion: '通邮｜标准挂号类-敏感｜2区', priceRegion: '2区' })).toBe('加拿大 · 2区')
})

it('does not infer nationwide pricing from absent metadata on legacy or unavailable rows', () => {
  expect(priceRegionLabel(row)).toBe('澳大利亚3区')
  expect(priceRegionLabel({ ...row, quoteRegion: '' })).toBe('')
  expect(priceRegionLabel({ ...row, quoteRegion: '全国统一' })).toBe('全国统一价')
})
