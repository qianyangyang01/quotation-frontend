import {describe,it,expect} from 'vitest'
import {majorChannelHistories,isTemporaryAttributeEquivalent,priceHistoryAttributes,attributeChannelHistories} from './logisticsMajorChannelHistory'
import mapping from './logisticsPriceWatchlistBindings.json'
import type {RealPriceSnapshot} from './logisticsRealPriceHistory'
import type {LogisticsPriceRow} from './logistics'

function snapshot(): RealPriceSnapshot {
  const channels=Object.values(mapping.bindings).map((b,i)=>({...b,ruleId:i,code:String(i),current:`v${i}`,attribute:'非液体化妆品',enabled:true,quoteReady:true}))
  channels.push({...channels[0],id:'unlisted',name:'表外渠道',current:'extra'})
  return {capturedAt:'2026-10-27T08:00:00Z',dataset:'test',channels,versions:channels.map(c=>({id:c.current,channel:c.id,number:1,status:'published',publishedAt:'2026-10-01T01:00:00Z',fileName:'test',rows:['US','DE','FR','AU'].map(countryCode=>({countryCode,areaName:countryCode,zoneName:'',pricePerKg:40,registrationFee:10,weightFromKg:0,weightToKg:30,minChargeWeightKg:0,startWeightKg:0,firstWeightKg:0,firstWeightPrice:0,nextWeightKg:0,nextWeightPrice:0,intervalPrice:0,surcharge:0,pricingModel:'per-kg'} as LogisticsPriceRow))}))}
}
describe('workbook main channels only',()=>{
 it('shows actual routes for destinations absent from the workbook, including priority Australia',()=>{
  const s=snapshot(),channel=s.channels.find(c=>c.id==='unlisted')!
  channel.attribute='普货'
  s.versions.find(v=>v.channel==='unlisted')!.rows.push({...s.versions[0]!.rows[0]!,countryCode:'NZ',areaName:'新西兰'})
  for(const country of ['NZ','AU']){
   expect(attributeChannelHistories(s,country,1,'普货/服装').rows.map(r=>r.channel.id)).toContain('unlisted')
   expect(attributeChannelHistories(s,country,1,'普货').rows.map(r=>r.channel.id)).toContain('unlisted')
  }
  channel.attribute='带电'
  expect(attributeChannelHistories(s,'NZ',1,'带电').rows.map(r=>r.channel.id)).toContain('unlisted')
  expect(attributeChannelHistories(s,'NZ',1,'普货/服装').rows).toEqual([])
  expect(attributeChannelHistories(s,'NZ',1,'以色列到门').rows).toEqual([])
  const reviewed=s.channels.find(c=>c.id===mapping.bindings['通邮挂号特货'].id)!
  reviewed.attribute='带电'
  s.versions.find(v=>v.channel===reviewed.id)!.rows.push({...s.versions[0]!.rows[0]!,countryCode:'NZ',areaName:'新西兰'})
  expect(attributeChannelHistories(s,'NZ',1,'带电').rows.some(r=>r.channel.id===reviewed.id)).toBe(true)
  expect(attributeChannelHistories(s,'NZ',1,'化妆品').rows).toEqual([])
  expect(attributeChannelHistories(s,'DE',1,'带电').rows.some(r=>r.channel.id==='unlisted')).toBe(false)
 })
 it('puts workbook attributes first and retains other system attributes without duplicates',()=>{
  const s=snapshot();s.channels[0]!.attribute='敏感货';s.channels[1]!.attribute='纯电池'
  const options=priceHistoryAttributes(s)
  expect(options.slice(0,4)).toEqual(['普货/服装','化妆品','保健品','带电'])
  expect(options).toEqual(expect.arrayContaining(['普货','服装','纯电','非液体化妆品','敏感货']))
  expect(options).not.toContain('纯电池')
  expect(options.length).toBe(new Set(options).size)
 })
 it('shows actual channels for additional attributes while preserving the workbook shortlist',()=>{
  const s=snapshot();s.channels.find(c=>c.id==='unlisted')!.attribute='纯电池'
  const before=JSON.stringify(s)
  expect(attributeChannelHistories(s,'DE',.5,'纯电').rows.map(r=>r.channel.id)).toEqual(['unlisted'])
  expect(attributeChannelHistories(s,'GB',.5,'纯电').rows).toEqual([])
  expect(attributeChannelHistories(s,'DE',.5,'普货/服装')).toEqual(majorChannelHistories(s,'DE',.5,'普货/服装'))
  expect(JSON.stringify(s)).toBe(before)
 })
 it('limits temporary equivalence to the reviewed channel and attribute pair without changing source data',()=>{
  const id=mapping.bindings['通邮挂号特货'].id
  expect(isTemporaryAttributeEquivalent(id,'化妆品','带电')).toBe(true)
  expect(isTemporaryAttributeEquivalent('unlisted','化妆品','带电')).toBe(false)
  expect(isTemporaryAttributeEquivalent(id,'保健品','带电')).toBe(false)
  const s=snapshot();s.channels.find(c=>c.id===id)!.attribute='带电'
  const before=JSON.stringify(s)
  const match=majorChannelHistories(s,'DE',.5,'化妆品').rows.find(r=>r.channel.id===id)!
  expect(match).toMatchObject({tableAttribute:'化妆品',temporaryEquivalent:true})
  expect(match.channel.attribute).toBe('带电')
  expect(JSON.stringify(s)).toBe(before)
 })
 it('keeps the country-specific workbook order, and does not show unrelated channels',()=>{
  const result=majorChannelHistories(snapshot(),'DE',.5,'普货/服装')
  expect(result.rows.map(r=>r.watchName)).toEqual(['万邦服装专线普货','联邮通服装','燕文专线惠选','云途普货CHC','通邮普货A','万邦专线挂号普货','顺丰CD'])
  expect(result.pending.map(p=>p.name)).toEqual(['万邦服装专线挂号'])
  expect(result.rows.some(r=>r.channel.id==='unlisted')).toBe(false)
 })
 it('uses workbook groups rather than silently excluding a listed route by system attribute',()=>{
  const s=snapshot()
  expect(majorChannelHistories(s,'US',.5,'普货/服装').rows.some(r=>r.watchName==='燕文化妆品')).toBe(true)
  expect(majorChannelHistories(s,'DE',.5,'化妆品').rows.map(r=>r.watchName)).toEqual(['通邮挂号特货','云途化妆品CHC','顺丰敏货','燕文化妆品'])
 })
 it('never falls back to all routes when no country shortlist exists, and fails closed on changed identities',()=>{
  const s=snapshot()
  expect(majorChannelHistories(s,'AU',.5,'').rows).toEqual([])
  const id=mapping.bindings['通邮普货A'].id
  s.channels.find(c=>c.id===id)!.name='另一条路线'
  const result=majorChannelHistories(s,'DE',.5,'普货/服装')
  expect(result.rows.some(r=>r.channel.id===id)).toBe(false)
  expect(result.pending.some(p=>p.name==='通邮普货A')).toBe(true)
 })
 it('deduplicates shared categories and preserves the ambiguous source-country warning',()=>{
  const s=snapshot(),result=majorChannelHistories(s,'US',.5,'')
  expect(result.rows.filter(r=>r.watchName==='燕文化妆品')).toHaveLength(1)
  expect(majorChannelHistories(s,'FR',.5,'保健品').pending[0].reason).toContain('N17标为德国')
 })
 it('shows all explicit candidate channels with separate histories, and never broadens to unrelated routes',()=>{
  const s=snapshot(),options=mapping.candidates['万邦专线挂号含电']
  options.forEach((b,i)=>{
   s.channels.push({...b,ruleId:100+i,code:'candidate'+i,current:'candidate-v'+i,attribute:'带电',enabled:true,quoteReady:true})
   s.versions.push({...s.versions[0]!,id:'candidate-v'+i,channel:b.id,rows:s.versions[0]!.rows.map(r=>({...r,pricePerKg:10+i*10}))})
  })
  const before=JSON.stringify(s),result=majorChannelHistories(s,'FR',1,'带电')
  const candidates=result.rows.filter(r=>r.candidateFor?.includes('万邦专线挂号含电'))
  expect(candidates.map(r=>r.channel.id)).toEqual(options.map(b=>b.id))
  expect(candidates.map(r=>r.current!.price)).toEqual([20,30,40])
  expect(candidates.every(r=>r.history.length===1&&r.current!.id===r.channel.current)).toBe(true)
  expect(result.pending.find(p=>p.name==='万邦专线挂号含电')!.reason).toContain('3 个系统候选渠道')
  expect(JSON.stringify(s)).toBe(before)
  s.channels.find(c=>c.id===options[0]!.id)!.name='未经核对的新名称'
  expect(majorChannelHistories(s,'FR',1,'带电').rows.some(r=>r.channel.id===options[0]!.id)).toBe(false)
 })
})
