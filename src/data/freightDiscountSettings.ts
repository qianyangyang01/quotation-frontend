import { readFinanceSetting, writeFinanceSetting } from '@/services/financeSettings'
import { api } from '@/services/http'
import { invalidatePublishedLogisticsCache } from './publishedLogisticsRepository'

export type FreightDiscountChannel = { channelId: string; channelCode: string; providerName: string; channelName: string; countries: Array<{ code: string; name: string }> }
export type FreightDiscountRule = Omit<FreightDiscountChannel, 'countries'> & { enabled: boolean; basis: 'full-freight' | 'base-excluding-linehaul'; defaultFactor: number; countries: Record<string, number> }
export type FreightDiscountSettings = { rules: FreightDiscountRule[]; updatedAt?: string }
export const ZHENGZHOU_CHANNEL_CODE = 'C-44fc48641d26ef2cab34'
export async function loadFreightDiscountChannels() {
  return api.get<FreightDiscountChannel[]>('/finance-settings/freight-discount-channels', { cache: 'no-store' })
}
export const ZHENGZHOU_DISCOUNT_COUNTRIES: Array<[string, string, number]> = [
  ['AU', '澳大利亚', .97], ['NZ', '新西兰', .99], ['JP', '日本', .94], ['DE', '德国', .97],
  ['PL', '波兰', .97], ['BE', '比利时', .97], ['GB', '英国', .97], ['ES', '西班牙', .97],
  ['ID', '印度尼西亚', .96], ['SA', '沙特阿拉伯', 1.01], ['KZ', '哈萨克斯坦', .96],
  ['FI', '芬兰', .96], ['FR', '法国', .96], ['IE', '爱尔兰', .96], ['NL', '荷兰', .96],
  ['VN', '越南', .96], ['BR', '巴西', .96], ['IT', '意大利', .92], ['HU', '匈牙利', .96],
  ['SG', '新加坡', 1], ['KR', '韩国', .92], ['TR', '土耳其', .96], ['TH', '泰国', .96],
  ['CH', '瑞士', .96], ['SE', '瑞典', .96], ['PT', '葡萄牙', .96], ['NO', '挪威', .96],
  ['MX', '墨西哥', .96], ['MY', '马来西亚', .96],
]
export function confirmedZhengzhouDiscount(channel: FreightDiscountChannel): FreightDiscountRule {
  return { ...channel, enabled: true, basis: 'base-excluding-linehaul', defaultFactor: 1.05, countries: Object.fromEntries(ZHENGZHOU_DISCOUNT_COUNTRIES.map(([code, , factor]) => [code, factor])) }
}
export function newFreightDiscountRule(channel: FreightDiscountChannel): FreightDiscountRule {
  return channel.channelCode === ZHENGZHOU_CHANNEL_CODE ? confirmedZhengzhouDiscount(channel)
    : { ...channel, enabled: false, basis: 'full-freight', defaultFactor: 1, countries: {} }
}
export function loadFreightDiscountSettings(): FreightDiscountSettings {
  const value = readFinanceSetting<FreightDiscountSettings>('freight-discount-settings')
  return structuredClone(value?.rules ? value : { rules: [] })
}
export function validateFreightDiscountSettings(settings: FreightDiscountSettings) {
  if (!Array.isArray(settings.rules) || settings.rules.length > 1000) throw new Error('渠道折扣须为列表，最多1000个渠道')
  const ids = new Set<string>()
  for (const rule of settings.rules) {
    if (!/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(rule.channelId) || ids.has(rule.channelId)) throw new Error('折扣渠道标识无效或重复')
    ids.add(rule.channelId)
    if (!['full-freight', 'base-excluding-linehaul'].includes(rule.basis)) throw new Error('请选择折扣适用费用')
    if (typeof rule.enabled !== 'boolean' || !rule.countries || Array.isArray(rule.countries) || Object.keys(rule.countries).length > 250) throw new Error('运费折扣设置无效')
    for (const code of Object.keys(rule.countries)) if (!/^[A-Z]{2}$/.test(code)) throw new Error('国家须使用两位国家代码')
    for (const factor of [rule.defaultFactor, ...Object.values(rule.countries)]) {
      if (typeof factor !== 'number' || !Number.isFinite(factor) || factor <= 0 || factor > 2) throw new Error('折扣系数须大于0且不超过2，例如0.97、1.01、1.05')
    }
  }
}
export async function saveFreightDiscountSettings(settings: FreightDiscountSettings) {
  validateFreightDiscountSettings(settings)
  const saved = await writeFinanceSetting('freight-discount-settings', { ...settings, updatedAt: new Date().toISOString() })
  // Revision checks also invalidate older tabs and reject saves priced with an old factor.
  await invalidatePublishedLogisticsCache()
  return saved
}
