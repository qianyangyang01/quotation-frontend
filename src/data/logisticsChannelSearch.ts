import {realChannelHistories,type RealPriceSnapshot,type ChannelPriceHistory} from './logisticsRealPriceHistory'
import {majorChannelHistories,type MajorChannelHistory} from './logisticsMajorChannelHistory'
import mapping from './logisticsPriceWatchlistBindings.json'

export type SearchableChannelHistory = MajorChannelHistory & {isMajor:boolean}
const normalize=(s:string)=>s.normalize('NFKC').toLowerCase().replace(/[\p{P}\p{Z}\p{S}\s]/gu,'')
export function channelSearchTerms(query:string) { return query.trim().split(/[\s,，;；]+/u).map(normalize).filter(Boolean) }
function subsequence(needle:string,haystack:string) {
  let i=0
  for(const c of haystack) if(c===needle[i])i++
  return i===needle.length
}
/** Partial Chinese names, abbreviations, case and punctuation; multiple terms may occur in any field. */
export function fuzzyChannelScore(query:string,fields:string[]) {
  const terms=channelSearchTerms(query),texts=fields.map(normalize)
  if(!terms.length)return null
  let score=0
  for(const term of terms) {
    const scores=texts.map(text=>text===term?0:text.startsWith(term)?1:text.includes(term)?2:subsequence(term,text)?3:Infinity)
    const best=Math.min(...scores)
    if(!Number.isFinite(best))return null
    score+=best
  }
  return score
}

export function searchCountryChannels(snapshot:RealPriceSnapshot,country:string,weight:number,query:string,history?:ChannelPriceHistory[]):SearchableChannelHistory[] {
  if(!channelSearchTerms(query).length)return []
  const major=majorChannelHistories(snapshot,country,weight,'').rows
  const order=new Map(major.map((r,i)=>[r.key,i]))
  const aliases=new Map<string,string[]>()
  for(const [name,binding] of Object.entries(mapping.bindings)) {
    if(!snapshot.channels.some(c=>c.id===binding.id&&c.name===binding.name&&c.provider===binding.provider))continue
    aliases.set(binding.id,[...(aliases.get(binding.id)||[]),name])
  }
  for(const row of major)if(row.candidateFor?.length)aliases.set(row.channel.id,[...(aliases.get(row.channel.id)||[]),...row.candidateFor])
  return (history??realChannelHistories(snapshot,country,weight)).flatMap(r=>{
    const score=fuzzyChannelScore(query,[r.channel.name,r.channel.provider,r.channel.provider+r.channel.name,r.channel.code,r.region,...(aliases.get(r.channel.id)||[])])
    if(score==null)return []
    const listed=major.find(m=>m.key===r.key)
    const item:SearchableChannelHistory=listed?{...listed,isMajor:true}:{...r,watchName:r.channel.name,tableAttribute:r.channel.attribute,temporaryEquivalent:false,isMajor:false}
    return [{item,score}]
  }).sort((a,b)=>Number(b.item.isMajor)-Number(a.item.isMajor)||a.score-b.score||(order.get(a.item.key)??999)-(order.get(b.item.key)??999)||a.item.channel.name.localeCompare(b.item.channel.name,'zh-CN',{numeric:true}))
    .map(r=>r.item)
}
