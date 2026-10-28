import watchlist from './logisticsPriceWatchlist.json'
import mapping from './logisticsPriceWatchlistBindings.json'
import equivalences from './logisticsLocalAttributeEquivalences.json'
import {selectableLogisticsAttributes,normalizeLogisticsAttribute} from './logisticsAttributes'
import { realChannelHistories, priorityPriceCountries, type RealPriceSnapshot, type ChannelPriceHistory } from './logisticsRealPriceHistory'

export const majorChannelCategories = watchlist.categories
export type MajorChannelHistory = ChannelPriceHistory & { watchName: string; tableAttribute: string; temporaryEquivalent: boolean; candidateFor?:string[] }

const systemAttributes=(value:string)=>(value||'普货').split(/[、,，;；|]/).map(normalizeLogisticsAttribute).filter(Boolean)
export function priceHistoryAttributes(snapshot:RealPriceSnapshot) {
  return [...new Set([...majorChannelCategories,...selectableLogisticsAttributes(snapshot.channels.flatMap(c=>systemAttributes(c.attribute)))])]
}

/** Workbook groups keep their shortlist; additional system attributes use their actual channel membership. */
export function attributeChannelHistories(snapshot:RealPriceSnapshot,country:string,weight:number,attribute:string) {
  if(majorChannelCategories.includes(attribute))return majorChannelHistories(snapshot,country,weight,attribute)
  const channels=snapshot.channels.filter(c=>systemAttributes(c.attribute).includes(attribute))
  const rows:MajorChannelHistory[]=realChannelHistories({...snapshot,channels},country,weight).map(r=>({...r,watchName:r.channel.name,tableAttribute:attribute,temporaryEquivalent:false}))
  return {rows,pending:[],listedCount:0}
}

// User-approved local display equivalence, scoped to the exact listed channel and attribute pair.
export function isTemporaryAttributeEquivalent(channelId: string, tableAttribute: string, systemAttribute: string) {
  return equivalences.entries.some(e=>e.channelId===channelId&&e.tableAttribute===tableAttribute&&e.systemAttribute===systemAttribute)
}

/** Explicit workbook shortlist, scoped by destination and workbook group. Never fall back to all routes. */
export function majorChannelHistories(snapshot: RealPriceSnapshot, countryCode: string, weight: number, category: string) {
  const country = priorityPriceCountries.find(([code]) => code === countryCode)?.[1]
  const entries = watchlist.rows.filter(r => r.country === country && (!category || r.category === category))
    .filter((r,i,all) => all.findIndex(other=>other.channel===r.channel)===i)
  const bindings: Record<string, {id:string;provider:string;name:string}> = mapping.bindings
  const unresolved: Record<string,string> = mapping.unresolved
  const candidates:Record<string,{id:string;provider:string;name:string}[]> = mapping.candidates
  const selected = entries.map(entry => ({entry, binding:bindings[entry.channel],options:candidates[entry.channel]||[]}))
  const accepted = snapshot.channels.filter(c=>selected.some(({binding,options})=>(binding?[binding]:options).some(b=>c.id===b.id&&c.provider===b.provider&&c.name===b.name)))
  const histories = realChannelHistories({...snapshot,channels:accepted},countryCode,weight)
  const rows: MajorChannelHistory[] = []
  const pending: {name:string;reason:string;cell:string}[] = []
  for (const {entry,binding,options} of selected) {
    const matched = binding ? histories.filter(r=>r.channel.id===binding.id) : []
    if (matched.length){
      for(const r of matched){
        const previous=rows.findIndex(row=>row.key===r.key)
        const row={...r,watchName:entry.channel,tableAttribute:entry.category,temporaryEquivalent:isTemporaryAttributeEquivalent(r.channel.id,entry.category,r.channel.attribute),candidateFor:previous>=0?rows[previous]!.candidateFor:undefined}
        if(previous<0)rows.push(row);else rows[previous]=row
      }
    }else{
      // Candidates are display references, never confirmed identities or merged price histories.
      const alternatives=binding?[]:options.flatMap(option=>histories.filter(r=>r.channel.id===option.id))
      for(const r of alternatives){
        const previous=rows.find(row=>row.key===r.key)
        if(previous)previous.candidateFor=[...new Set([...(previous.candidateFor||[]),entry.channel])]
        else rows.push({...r,watchName:r.channel.name,tableAttribute:entry.category,temporaryEquivalent:false,candidateFor:[entry.channel]})
      }
      const count=new Set(alternatives.map(r=>r.channel.id)).size
      pending.push({name:entry.channel,cell:entry.cell,reason:count?`名称未完全对应，已分别展示 ${count} 个系统候选渠道，各自价格独立；对应关系待确认`:unresolved[entry.channel] || (accepted.some(c=>c.id===binding?.id) ? '渠道未启用、未通过发布检查或缺少该国家的价格记录' : '尚未确认对应的系统渠道')})
    }
  }
  const issues = watchlist.issues.filter(r=>r.country===country&&(!category||r.category===category))
  for (const issue of issues) pending.push({name:issue.channel,cell:issue.cell,reason:`原表${issue.countryCell}标为${issue.actualCountry}，国家归属待确认`})
  return {rows,pending,listedCount:entries.length+issues.length}
}
