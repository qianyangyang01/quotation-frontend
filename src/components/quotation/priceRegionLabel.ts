import type { QuotationMatrixRow } from './types'

type RegionRow = Pick<QuotationMatrixRow, 'country' | 'carrier' | 'rule' | 'quoteRegion' | 'priceRegion'>

/** Display only. Never replace quoteRegion: it identifies selections, templates and saved quotes. */
export function priceRegionLabel(row?: RegionRow): string {
  if (!row) return ''
  const prefix = `${row.carrier}｜${row.rule}｜`
  const selected = row.quoteRegion?.trim().replace(prefix, '') || ''
  const actual = row.priceRegion?.trim()
  if (actual === '' || actual === '全国统一' || (actual === undefined && selected === '全国统一')) return '全国统一价'
  // Missing metadata (e.g. unavailable/legacy rows) is not evidence of national pricing.
  const regions = (actual ?? selected).split(/[/／、,，;；|]/).map(value => value.trim()).filter(Boolean)
  const normalize = (value: string) => value.replace(/^澳大利亚/, '').replace(/一区/g, '1区').replace(/二区/g, '2区').replace(/三区/g, '3区').replace(/四区/g, '4区')
  const region = regions.find(value => normalize(value) === normalize(selected)) || regions.join(' / ')
  return !region ? '' : region.startsWith(row.country) ? region : `${row.country} · ${region}`
}
