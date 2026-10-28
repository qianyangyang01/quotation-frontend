import type {RealPriceSnapshot} from './logisticsRealPriceHistory'

/** Reject damaged display responses before they replace the last known good snapshot. */
export function assertPriceSnapshot(value:unknown):asserts value is RealPriceSnapshot {
  const bad=()=>{throw new Error('价格展示数据有误，请稍后手动刷新')}
  const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
  const date=(v:unknown)=>typeof v==='string'&&Number.isFinite(Date.parse(v))
  if(!object(value)||!date(value.capturedAt)||!Array.isArray(value.channels)||!Array.isArray(value.versions))return bad()
  const channels=new Set<string>(),versions=new Set<string>()
  for(const c of value.channels){
    if(!object(c)||typeof c.id!=='string'||!c.id||channels.has(c.id))return bad()
    if(!['name','provider','attribute','code'].every(key=>typeof c[key]==='string')||typeof c.enabled!=='boolean'||typeof c.quoteReady!=='boolean')return bad()
    channels.add(c.id)
  }
  for(const v of value.versions){
    if(!object(v)||typeof v.id!=='string'||!v.id||versions.has(v.id)||typeof v.channel!=='string'||!channels.has(v.channel))return bad()
    if(!date(v.publishedAt)||!Number.isInteger(v.number)||!['published','superseded'].includes(String(v.status))||!Array.isArray(v.rows))return bad()
    versions.add(v.id)
    for(const row of v.rows){
      if(!object(row)||typeof row.countryCode!=='string'||(row.zoneName!=null&&typeof row.zoneName!=='string'))return bad()
    }
    if(v.preparedPrices!==undefined){
      if(!object(v.preparedPrices))return bad()
      for(const regions of Object.values(v.preparedPrices)){
        if(!object(regions))return bad()
        for(const point of Object.values(regions)){
          if(!object(point)||typeof point.reason!=='string'||typeof point.model!=='string')return bad()
          for(const field of ['price','rate','fee','chargeWeight','intervalPrice']){
            const number=point[field]
            if(number!=null&&(typeof number!=='number'||!Number.isFinite(number)||number<0))return bad()
          }
          if(point.price===undefined)return bad()
        }
      }
    }
  }
  for(const c of value.channels){
    if(c.enabled&&c.quoteReady&&!value.versions.some(v=>v.channel===c.id&&v.id===c.current))return bad()
  }
}
