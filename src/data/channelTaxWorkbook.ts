import JSZip from 'jszip'
import { logisticsChannels } from './logistics'
import { normalizeFinanceTaxSettings, type FinanceTaxSettings } from './financeTaxSettings'
import type { ChannelTaxRule } from './channelTaxRules'

const columns: Record<string, string> = { 欧盟关税: '欧盟', 美国关税: '美国', 新西兰: '新西兰', 墨西哥关税: '墨西哥', 阿拉伯联合酋长国关税: '阿拉伯联合酋长国', 沙特阿拉伯关税: '沙特阿拉伯', 哥伦比亚: '哥伦比亚', 约旦关税: '约旦', '摩洛哥-增值税': '摩洛哥', 阿曼: '阿曼' }
const normalize = (value: unknown) => String(value ?? '').replace(/[\s（）()－—-]/g, '')
// Finance confirmed these six spreadsheet rows belong to the existing 闪电猴 channels.
const shandianhouNames = new Set(['全球专线带电-美国', '全球专线带电-美国（偏远）', '全球专线敏感-美国', '全球专线敏感-美国（偏远）', '全球专线普货-美国', '全球专线普货-美国（偏远）'].map(normalize))
export type TaxWorkbookRow = { provider: string; channel: string; values: Record<string, string | number>; row: number }
export function applyTaxWorkbookRows(rows: TaxWorkbookRow[], settings: FinanceTaxSettings, catalog = logisticsChannels) {
  const next = normalizeFinanceTaxSettings(JSON.parse(JSON.stringify(settings)))
  const unmatched: string[] = []; const assigned = new Set<string>(); let count = 0
  for (const row of rows) {
    const provider = row.provider === '云速递' && shandianhouNames.has(normalize(row.channel)) ? '闪电猴' : row.provider
    const channels = catalog.filter(item => normalize(item.carrier) === normalize(provider) && (normalize(item.channel) === normalize(row.channel)
      // This is the same approved replacement channel; never create or rename logistics data here.
      || (provider === '燕文' && row.channel === '中邮郑州线下E邮宝' && item.channelCode === 'C-44fc48641d26ef2cab34')))
    const unique = new Map(channels.map(item => [`${item.ruleId}::${item.carrier}::${item.channelCode || item.channel}`, item]))
    if (unique.size !== 1) { unmatched.push(`${row.provider} / ${row.channel}`); continue }
    const key = [...unique.keys()][0]!
    for (const [heading, value] of Object.entries(row.values)) {
      const countryName = columns[heading]
      if (!countryName) continue // 偏远费和处理费保持独立，不能作为国家关税导入。
      const identity = `${countryName}|${key}`
      if (assigned.has(identity)) throw new Error(`同一国家渠道重复：第 ${row.row} 行 ${provider} / ${row.channel}`)
      assigned.add(identity)
      const rule: ChannelTaxRule = { key, mode: 'fixed-order', amount: 0, perKg: 0, currency: 'USD', source: `关税表 · 第${row.row}行 · ${heading}` }
      if (value === '无') rule.mode = 'no-tax'
      else if (value === '含') rule.mode = 'exempt'
      else if (value === '不发') rule.mode = 'unavailable'
      else if (typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 1_000_000) rule.amount = value
      else if (normalize(value) === '重量*1.5+0.6' && countryName === '欧盟' && row.provider === '云途' && /CHC$/i.test(row.channel)) Object.assign(rule, { mode: 'weight', amount: 0.6, perKg: 1.5, currency: 'EUR' })
      else throw new Error(`第 ${row.row} 行“${heading}”内容无法识别：${value}`)
      let country = next.countries.find(item => item.country === countryName)
      if (!country) { country = { country: countryName, selected: true, enabled: true, fixedFeeUsd: 0, sortOrder: next.countries.length + 1 }; next.countries.push(country) }
      country.selected = true; country.enabled = true
      const rules = new Map((country.channelRules || []).map(item => [item.key, item])); rules.set(key, rule); country.channelRules = [...rules.values()]; count++
    }
  }
  return { settings: next, count, unmatched, message: `已读取 ${rows.length} 个渠道，应用 ${count} 项税费到未保存草稿。无=0、含=已包含、不发=该目的地不支持。偏远费与处理费列未并入关税。${unmatched.length ? `未匹配 ${unmatched.length} 个渠道，保留原设置：${unmatched.join('；')}` : ''} 请核对后保存并发布。` }
}
export async function importChannelTaxWorkbook(file: File, settings: FinanceTaxSettings, catalog = logisticsChannels) {
  if (!/\.xlsx$/i.test(file.name) || file.size > 5 * 1024 * 1024) throw new Error('请上传不超过 5MB 的 .xlsx 关税表')
  const zip = await JSZip.loadAsync(await file.arrayBuffer())
  const xml = (value: string) => { if (value.length > 10_000_000) throw new Error('表格内容过大'); const doc = new DOMParser().parseFromString(value, 'application/xml'); if (doc.querySelector('parsererror')) throw new Error('表格结构损坏'); return doc }
  const sharedFile = zip.file('xl/sharedStrings.xml')
  const strings = sharedFile ? [...xml(await sharedFile.async('string')).getElementsByTagName('si')].map(item => item.textContent || '') : []
  for (const path of Object.keys(zip.files).filter(name => /^xl\/worksheets\/sheet\d+\.xml$/.test(name))) {
    const doc = xml(await zip.file(path)!.async('string')), rows = new Map<number, (string | number)[]>()
    for (const cell of doc.getElementsByTagName('c')) {
      const address = cell.getAttribute('r') || '', row = Number(address.match(/\d+$/)?.[0]), letters = address.match(/^[A-Z]+/)?.[0] || ''
      const column = [...letters].reduce((sum, letter) => sum * 26 + letter.charCodeAt(0) - 64, 0) - 1
      if (!row || row > 2001 || column < 0 || column > 50) continue
      const type = cell.getAttribute('t'), raw = cell.getElementsByTagName('v')[0]?.textContent || ''
      const value = type === 's' ? strings[Number(raw)] || '' : type === 'inlineStr' ? cell.getElementsByTagName('is')[0]?.textContent || '' : type === 'str' ? raw : raw !== '' && Number.isFinite(Number(raw)) ? Number(raw) : raw
      const values = rows.get(row) || []; values[column] = value; rows.set(row, values)
    }
    const header = [...rows].find(([, cells]) => normalize(cells[0]) === '物流商' && normalize(cells[1]) === '标准渠道名')
    if (!header) continue
    const headings = header[1].map(value => String(value).replace(/\s/g, ''))
    const input: TaxWorkbookRow[] = [...rows].filter(([index, cells]) => index > header[0] && (cells[0] || cells[1])).map(([index, cells]) => ({ provider: String(cells[0] || '').trim(), channel: String(cells[1] || '').trim(), row: index, values: Object.fromEntries(headings.slice(2).map((heading, c) => [heading, cells[c + 2] ?? ''])) }))
    if (!input.length) throw new Error('未找到渠道税费数据')
    return applyTaxWorkbookRows(input, settings, catalog)
  }
  throw new Error('未找到“物流商 / 标准渠道名”的关税表头')
}
