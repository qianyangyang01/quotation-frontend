// @vitest-environment happy-dom
import { expect, it, vi } from 'vitest'
import JSZip from 'jszip'
import { batchPriceWorkbook, loadExportVersionRows } from './logisticsBatchWorkbook'
import { batchExportFixture } from '@/data/logisticsBatchExport.fixture'
import { prepareBatchExport } from '@/data/logisticsBatchExport'
import { allCountryPriceUpdateGroups } from '@/data/logisticsUpdateOverview'
import { request } from './http'
vi.mock('./http', () => ({ request: vi.fn() }))
it('writes five filterable centered sheets with typed prices, safe text and frozen headers', async () => {
  const s = batchExportFixture(), batch = allCountryPriceUpdateGroups(s, 1).find(b => b.id.startsWith('window:'))!
  const data = await prepareBatchExport(s, batch, async () => [])
  data.rows[0]!.channel.name = '=HYPERLINK("bad") & <text>'
  const zip = await JSZip.loadAsync(await (await batchPriceWorkbook(data)).arrayBuffer())
  const parse = async (name: string) => new DOMParser().parseFromString(await zip.file(name)!.async('string'), 'application/xml')
  expect((await parse('xl/workbook.xml')).querySelectorAll('sheet')).toHaveLength(5)
  const styles = await parse('xl/styles.xml')
  expect(styles.querySelectorAll('cellXfs xf alignment[horizontal="center"][vertical="center"]')).toHaveLength(11)
  for (let i = 1; i <= 5; i++) {
    const sheet = await parse(`xl/worksheets/sheet${i}.xml`), table = await parse(`xl/tables/table${i}.xml`)
    expect(sheet.querySelector('parsererror')).toBeNull();expect(table.querySelector('parsererror')).toBeNull()
    expect(table.querySelector('autoFilter')?.getAttribute('ref')).toMatch(/^A6:/)
    expect(sheet.querySelector('pane')?.getAttribute('ySplit')).toBe('6')
  }
  const detail = await parse('xl/worksheets/sheet2.xml')
  expect(detail.querySelector('c[r="F7"] v')?.textContent).toBe('96')
  expect(detail.querySelector('c[r="B7"]')?.getAttribute('t')).toBe('inlineStr')
  expect(detail.querySelector('c[r="B7"] t')?.textContent).toBe('=HYPERLINK("bad") & <text>')
  expect(detail.querySelectorAll('f')).toHaveLength(0)
  const empty = { ...data, rows: data.rows.filter(r => r.category === 'up') }
  const emptyZip = await JSZip.loadAsync(await (await batchPriceWorkbook(empty)).arrayBuffer())
  expect(await emptyZip.file('xl/worksheets/sheet3.xml')!.async('string')).toContain('无记录')
})
it('uses only a read request and rejects wrong version identity or incomplete data', async () => {
  const version = batchExportFixture().versions[0]!
  vi.mocked(request).mockResolvedValue({ id: version.id, channelId: version.channel, status: 'superseded', rows: version.rows, rowCount: 3 })
  expect(await loadExportVersionRows(version)).toHaveLength(3)
  expect(vi.mocked(request).mock.calls.at(-1)?.[1]?.method).toBeUndefined()
  vi.mocked(request).mockResolvedValue({ id: 'wrong', channelId: version.channel, status: 'published', rows: [] })
  await expect(loadExportVersionRows(version)).rejects.toThrow('资料不完整')
})
