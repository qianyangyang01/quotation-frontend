import { calculateLogisticsFee, logisticsUnavailableReason, type LogisticsPriceRow, type LogisticsRule } from './logistics'
import { calendarDay, weightedPricePeriod } from './logisticsPriceTrendAnalytics'

export interface HistoryChannel { id: string; ruleId: number; provider: string; code: string; current: string; name: string; attribute: string; enabled: boolean; quoteReady: boolean }
export type PreparedPrice = Pick<HistoryPoint,'price'|'rate'|'fee'|'chargeWeight'|'model'|'intervalPrice'|'reason'>
export interface PublishedPriceVersion { id: string; channel: string; number: number; status: string; publishedAt: string; fileName: string; rollbackFromVersionId?: string; rows: (LogisticsPriceRow & { currency?: string })[]; preparedPrices?:Record<string,Record<string,PreparedPrice>> }
export interface RealPriceSnapshot { capturedAt: string; sourceRevision?:string; dataset: string; channels: HistoryChannel[]; versions: PublishedPriceVersion[] }
export interface HistoryPoint { id: string; number: number; timestamp: string; date: string; price: number | null; rate: number | null; fee: number | null; chargeWeight: number | null; model: string; intervalPrice: number | null; reason: string; file: string }
export interface ChannelPriceHistory { key: string; channel: HistoryChannel; region: string; history: HistoryPoint[]; current: HistoryPoint | undefined }
export const priorityPriceCountries = [ ['US','美国'],['CA','加拿大'],['GB','英国'],['DE','德国'],['FR','法国'],['ES','西班牙'],['IT','意大利'],['NL','荷兰'],['BE','比利时'],['AU','澳大利亚'] ] as const
export const publicationDate = (timestamp: string) => new Date(Date.parse(timestamp) + 8 * 3600000).toISOString().slice(0,10)
export const publicationTime = (timestamp: string) => new Date(Date.parse(timestamp) + 8 * 3600000).toISOString().slice(0,16).replace('T',' ')
const zones = (row: LogisticsPriceRow) => row.zoneName ? row.zoneName.split(/[/／、,，;；|]/).map(x=>x.trim()).filter(Boolean) : ['全国统一']
const normalizeCanada = (name: string) => name.replace(/^加拿大/,'').replace(/[一二三四五六七八九]/g, x=>String('一二三四五六七八九'.indexOf(x)+1))

/** Version IDs, not labels, bind history to the same real channel. No name matching or synthetic prices. */
export function realChannelHistories(snapshot: RealPriceSnapshot, country: string, weight: number, attribute = ''): ChannelPriceHistory[] {
  const result: ChannelPriceHistory[] = []
  const preparedKey=country+'|'+weight
  for (const channel of snapshot.channels) {
    if (!channel.enabled || !channel.quoteReady) continue
    if (attribute && !(channel.attribute || '普货').split(/[、,，;；|]/).includes(attribute)) continue
    const versions = snapshot.versions.filter(v=>v.channel===channel.id && v.publishedAt && ['published','superseded'].includes(v.status))
      .sort((a,b)=>Date.parse(a.publishedAt)-Date.parse(b.publishedAt)||a.number-b.number)
    const currentVersion = versions.find(v=>v.id===channel.current)
    if (!currentVersion || !versions.some(v=>v.preparedPrices?.[preparedKey]||v.rows.some(r=>r.countryCode===country))) continue
    const regions = [...new Set(versions.flatMap(v=>v.preparedPrices?Object.keys(v.preparedPrices[preparedKey]||{}):v.rows.filter(r=>r.countryCode===country&&!r.zoneExclude).flatMap(zones)))].sort((a,b)=>a.localeCompare(b,'zh-CN',{numeric:true}))
    for (const region of regions) {
      const history = versions.map(v => {
        if(v.preparedPrices){
          const point=v.preparedPrices[preparedKey]?.[region]
          return {id:v.id,number:v.number,timestamp:v.publishedAt,date:publicationDate(v.publishedAt),file:v.fileName||'未记录源文件名',
            ...(point||{price:null,rate:null,fee:null,chargeWeight:null,model:'',intervalPrice:null,reason:'此版本无该国家 / 分区'})}
        }
        const prices = v.rows.filter(r=>r.countryCode===country)
        const rule: LogisticsRule = { id:channel.ruleId, name:channel.name, englishName:channel.code, type:'专线', currency:'CNY', status:'启用', published:'发布', dates:'', users:'', phoneRequired:false, areaCount:1, priceRowCount:prices.length, prices,
          billingVerified:true, relations:[{carrier:channel.provider,channel:channel.name,channelCode:channel.code,discounts:''}] }
        const quoteRegion = country==='AU'||region==='全国统一' ? region : `${channel.provider}｜${channel.name}｜${country==='CA'?normalizeCanada(region):region}`
        const hasRegion = prices.some(r=>!r.zoneExclude&&zones(r).includes(region))
        const fee = hasRegion ? calculateLogisticsFee(rule,country,weight,['普货'],undefined,quoteRegion) : null
        const currency = (fee?.price as LogisticsPriceRow & { currency?: string } | undefined)?.currency || 'CNY'
        const valid = fee && currency==='CNY'
        return { id:v.id, number:v.number, timestamp:v.publishedAt, date:publicationDate(v.publishedAt), price:valid?fee.total:null,
          rate:valid?fee.price.pricePerKg:null, fee:valid?fee.price.registrationFee:null, chargeWeight:valid?fee.chargeWeightKg:null,
          model:valid?fee.price.pricingModel||'per-kg':'', intervalPrice:valid?fee.price.intervalPrice:null,
          reason:!hasRegion?'此版本无该国家 / 分区':currency!=='CNY'?`原币种 ${currency}，未换算`:fee?'':logisticsUnavailableReason(rule,country,weight,['普货'],quoteRegion), file:v.fileName||'未记录源文件名' }
      })
      result.push({ key:`${channel.id}|${region}`, channel, region, history, current:history.find(v=>v.id===channel.current) })
    }
  }
  return result.sort((a,b)=>Number(b.current?.price!=null)-Number(a.current?.price!=null)||a.channel.provider.localeCompare(b.channel.provider,'zh-CN')||a.channel.name.localeCompare(b.channel.name,'zh-CN')||a.region.localeCompare(b.region,'zh-CN',{numeric:true}))
}

export function comparedVersions(item: ChannelPriceHistory, mode: string) {
  const i=item.history.findIndex(v=>v.id===item.channel.current)
  const older=item.history[i-2], previous=item.history[i-1], current=item.history[i]
  const from=mode==='currentPrevious'?previous:older, to=mode==='previousOlder'?previous:current
  const change=from?.price!=null&&to?.price!=null?Math.round((to.price-from.price)*100)/100:null
  return { older, previous, current, from, to, change, percent:change!=null&&from?.price?change/from.price*100:null }
}
export function recordedYearLow(item: ChannelPriceHistory, capturedAt: string) {
  const year=publicationDate(capturedAt).slice(0,4)
  const values=item.history.filter(v=>v.date.startsWith(year)&&v.price!=null&&Date.parse(v.timestamp)<=Date.parse(capturedAt))
  if(!values.length)return null
  return values.reduce((low,v)=>v.price!<low.price!?v:low)
}
/** Day-weighted statistics use the final publication of each China calendar day. No backfill before first record. */
export function recordedPeriod(item: ChannelPriceHistory, from: string, through: string, capturedAt: string) {
  if(calendarDay(through)>calendarDay(publicationDate(capturedAt)))return null
  const days=new Map<string,HistoryPoint>()
  for(const v of item.history) days.set(v.date,v)
  return weightedPricePeriod([...days.values()].map(v=>({date:v.date,rate:v.price,fee:0})),1,from,through)
}
