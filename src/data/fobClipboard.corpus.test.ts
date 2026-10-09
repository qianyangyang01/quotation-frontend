// @vitest-environment happy-dom
// Opt-in private workbook regression. Raw business data stays outside Git.
import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { readFobClipboard } from './fobClipboard'

const path = process.env.FOB_SOURCE_CELLS
it.skipIf(!path)('maps every original FOB workbook row without losing tiers, freight, or column alignment', () => {
  const source = JSON.parse(readFileSync(path!, 'utf8')) as { header: string[]; rows: { sourceRow: number; cells: string[] }[] }
  const tsv = (rows: string[][]) => rows.map(row => row.map(cell => `"${cell.replace(/"/g, '""')}"`).join('\t')).join('\r\n')
  const data = (text: string) => ({ getData: (type: string) => type === 'text/plain' ? text : '' })
  expect(source.rows).toHaveLength(317)
  for (let start = 0; start < source.rows.length; start += 100) {
    const batch = source.rows.slice(start, start + 100), cells = batch.map(row => Array.from({ length: source.header.length }, (_, i) => row.cells[i] || ''))
    for (const pasted of [cells, [source.header, ...cells], cells.map(row => row.slice(2, 16))]) {
      const result = readFobClipboard(data(tsv(pasted)))
      expect(result).toHaveLength(batch.length)
      for (const [i, row] of result.entries()) {
        const original = cells[i]!
        expect(row.sku, `源表第${batch[i]!.sourceRow}行`).toBe(original[2]!.toUpperCase().replace(/\s/g, ''))
        expect(row.priceRaw || '').toBe(original[13]!.trim())
        expect(row.freightRaw || '').toBe(original[15]!.trim())
        expect(row.moqRaw || '').toBe(original[12]!.trim())
        expect(row.weightRaw || '').toBe((original[8] || original[7] || '').trim())
        expect(row).not.toHaveProperty('taxIncludedPriceCny')
      }
    }
  }
})
