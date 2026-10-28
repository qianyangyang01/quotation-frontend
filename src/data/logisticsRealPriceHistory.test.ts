import {describe,it,expect} from 'vitest'
import {realChannelHistories,comparedVersions,recordedPeriod,recordedYearLow,publicationDate,type RealPriceSnapshot} from './logisticsRealPriceHistory'
import type {LogisticsPriceRow} from './logistics'
const row=(zoneName='',pricePerKg=20):LogisticsPriceRow=>({areaName:'澳大利亚',countryCode:'AU',zoneName,pricePerKg,registrationFee:5,weightFromKg:0,weightToKg:30,minChargeWeightKg:1,startWeightKg:0,firstWeightKg:0,firstWeightPrice:0,nextWeightKg:0,nextWeightPrice:0,intervalPrice:0,surcharge:0,billingStepKg:0.5,pricingModel:'per-kg',etaMinDays:5,etaMaxDays:10,prohibitedMarks:'',allowedMarks:'',maxPerimeterCm:0,maxSideCm:0,volumeDivisor:0,fuelSurchargeRate:0,prohibitGeneralCargo:false,volumetric:false,phoneRequired:false,zoneExclude:false})
const snapshot=():RealPriceSnapshot=>({capturedAt:'2026-10-27T08:00:00Z',dataset:'test',channels:[{id:'a',ruleId:1,provider:'甲',code:'A',current:'v3',name:'真实渠道',attribute:'普货',enabled:true,quoteReady:true}],versions:[
 {id:'v1',channel:'a',number:1,status:'superseded',publishedAt:'2026-09-01T01:00:00Z',fileName:'原价',rows:[row('1区',20),row('2区',40)]},
 {id:'v2',channel:'a',number:2,status:'superseded',publishedAt:'2026-10-01T01:00:00Z',fileName:'新版',rows:[row('1区',30)]},
 {id:'v3',channel:'a',number:3,status:'published',publishedAt:'2026-10-10T01:00:00Z',fileName:'本版',rows:[row('1区',35),row('2区',45)]},
]})
describe('real published logistics history',()=>{
 it('selects the first repeated annual low, including rollback, and excludes other years, future prices and gaps',()=>{
  const s=snapshot(),base=s.versions[0]!
  s.capturedAt='2026-10-27T08:00:00Z'
  s.versions=[
   {...base,id:'old',number:1,publishedAt:'2025-12-31T15:59:59Z',rows:[row('1区',1)]},
   {...base,id:'first',number:2,publishedAt:'2025-12-31T16:00:00Z',rows:[row('1区',20)]},
   {...base,id:'gap',number:3,publishedAt:'2026-05-01T01:00:00Z',rows:[]},
   {...base,id:'repeat',number:4,publishedAt:'2026-06-01T01:00:00Z',rollbackFromVersionId:'first',rows:[row('1区',20)]},
   {...base,id:'current',number:5,status:'published',publishedAt:'2026-09-01T01:00:00Z',rows:[row('1区',30)]},
   {...base,id:'future',number:6,publishedAt:'2026-11-01T01:00:00Z',rows:[row('1区',2)]},
   {...base,id:'draft',number:7,status:'draft',publishedAt:'2026-10-01T01:00:00Z',rows:[row('1区',3)]},
  ].reverse()
  s.channels[0]!.current='current'
  const item=realChannelHistories(s,'AU',1)[0]!
  expect(recordedYearLow(item,s.capturedAt)).toMatchObject({id:'first',price:25,date:'2026-01-01'})
  expect(recordedYearLow(item,'2025-12-31T15:59:59Z')).toMatchObject({id:'old',price:6,date:'2025-12-31'})
  expect(recordedYearLow(item,'2026-12-31T16:01:00Z')).toBeNull()
  expect(recordedYearLow({...item,history:item.history.map(v=>({...v,price:null}))},s.capturedAt)).toBeNull()
 })
 it('reads publication-time results without original price rows and keeps missing version pairs unavailable',()=>{
  const s=snapshot()
  s.versions.forEach((v,i)=>{v.rows=[];v.preparedPrices={'AU|1':{'1区':{price:[25,35,40][i]!,rate:[20,30,35][i]!,fee:5,chargeWeight:1,model:'per-kg',intervalPrice:0,reason:''}}}})
  s.versions[0]!.preparedPrices!['AU|1']!['2区']={price:45,rate:40,fee:5,chargeWeight:1,model:'per-kg',intervalPrice:0,reason:''}
  s.versions[2]!.preparedPrices!['AU|1']!['2区']={price:50,rate:45,fee:5,chargeWeight:1,model:'per-kg',intervalPrice:0,reason:''}
  const result=realChannelHistories(s,'AU',1)
  expect(result[0]!.history.map(v=>v.price)).toEqual([25,35,40]);expect(result[1]!.history.map(v=>v.price)).toEqual([45,null,50])
  expect(comparedVersions(result[0]!,'currentPrevious').change).toBe(5)
  expect(comparedVersions(result[1]!,'currentPrevious').change).toBeNull()
 })
 it('moves exact version pairs after publication, preserves old prices, and records rollback as a new version',()=>{
  const s=snapshot(),before=realChannelHistories(s,'AU',1)[0]!
  const original=JSON.stringify(s.versions)
  s.versions.push({...s.versions[2]!,id:'v4',number:4,status:'draft',publishedAt:'',rows:[row('1区',25),row('2区',30)]})
  expect(realChannelHistories(s,'AU',1)[0]!.history).toEqual(before.history)
  s.versions[2]!.status='superseded';s.versions[3]!.status='published';s.versions[3]!.publishedAt='2026-10-20T01:00:00Z';s.channels[0]!.current='v4'
  const published=realChannelHistories(s,'AU',1)[0]!
  expect(published.history.slice(0,3)).toEqual(before.history)
  expect(comparedVersions(published,'currentPrevious')).toMatchObject({change:-10,percent:-25,from:{id:'v3'},to:{id:'v4'}})
  expect(comparedVersions(published,'previousOlder')).toMatchObject({change:5,from:{id:'v2'},to:{id:'v3'}})
  expect(comparedVersions(published,'currentOlder')).toMatchObject({change:-5,from:{id:'v2'},to:{id:'v4'}})
  s.versions[3]!.status='superseded';s.versions.push({...s.versions[0]!,id:'v5',number:5,status:'published',publishedAt:'2026-10-21T01:00:00Z',rollbackFromVersionId:'v1'});s.channels[0]!.current='v5'
  const rolled=realChannelHistories(s,'AU',1)[0]!
  expect(rolled.history.map(v=>v.price)).toEqual([25,35,40,30,25])
  expect(comparedVersions(rolled,'currentPrevious')).toMatchObject({change:-5,from:{id:'v4'},to:{id:'v5'}})
  expect(s.versions.slice(0,3).map(v=>v.rows)).toEqual(JSON.parse(original).map((v:{rows:unknown})=>v.rows))
 })
 it('uses the compared starting price as the percentage denominator and handles zero correctly',()=>{
  const s=snapshot();s.versions.forEach((v,i)=>{v.rows=[{...row('1区',i===0?0:i===1?100:80),registrationFee:0}]})
  const item=realChannelHistories(s,'AU',1)[0]!
  expect(comparedVersions(item,'currentPrevious')).toMatchObject({change:-20,percent:-20})
  // Zero unit rates are rejected by the billing engine, not treated as free freight.
  expect(comparedVersions(item,'currentOlder')).toMatchObject({change:null,percent:null})
  item.history[0]!.price=0
  expect(comparedVersions(item,'currentOlder')).toMatchObject({change:80,percent:null})
  s.versions[2]!.rows[0]!.pricePerKg=100
  expect(comparedVersions(realChannelHistories(s,'AU',1)[0]!,'currentPrevious')).toMatchObject({change:0,percent:0})
 })
 it('keeps regions separate, uses minimum and stepped charge weight, and includes operation fees',()=>{
  const s=snapshot(), rows=realChannelHistories(s,'AU',0.5,'普货')
  expect(rows).toHaveLength(2)
  expect(rows[0].history.map(v=>v.price)).toEqual([25,35,40])
  expect(rows[1].history.map(v=>v.price)).toEqual([45,null,50])
  expect(realChannelHistories(s,'AU',1.01)[0].current?.price).toBe(57.5)
 })
 it('compares exact version pairs and never skips missing prices to invent a previous price',()=>{
  const [a,b]=realChannelHistories(snapshot(),'AU',0.5)
  expect(comparedVersions(a,'currentPrevious').change).toBe(5)
  expect(comparedVersions(a,'previousOlder').change).toBe(10)
  expect(comparedVersions(a,'currentOlder').change).toBe(15)
  expect(comparedVersions(b,'currentPrevious').change).toBeNull()
  expect(comparedVersions(b,'currentOlder').change).toBe(5)
 })
 it('excludes drafts and disabled channels, and matches country and attribute',()=>{
  const s=snapshot();s.versions.push({...s.versions[0],id:'draft',number:4,status:'draft',publishedAt:'2026-10-26T00:00:00Z'})
  expect(realChannelHistories(s,'AU',0.5)[0].history).toHaveLength(3)
  expect(realChannelHistories(s,'DE',0.5)).toHaveLength(0)
  expect(realChannelHistories(s,'AU',0.5,'带电')).toHaveLength(0)
  s.channels[0].enabled=false;expect(realChannelHistories(s,'AU',0.5)).toHaveLength(0)
 })
 it('uses China publication dates and reports only evidenced minima, not invented year coverage',()=>{
  expect(publicationDate('2026-09-01T18:00:00Z')).toBe('2026-09-02')
  const s=snapshot(),r=realChannelHistories(s,'AU',0.5)[0]
  expect(recordedYearLow(r,s.capturedAt)).toMatchObject({price:25,date:'2026-09-01'})
  expect(recordedPeriod(r,'2025-01-01','2026-10-01',s.capturedAt)).toBeNull()
  expect(recordedPeriod(r,'2026-09-01','2026-10-01',s.capturedAt)?.avg).toBeCloseTo((25*30+35)/31)
 })
 it('handles same-day publication ordering and leaves conflicting billing rows unpriced',()=>{
  const s=snapshot();s.versions[1].publishedAt='2026-09-01T02:00:00Z'
  const r=realChannelHistories(s,'AU',0.5)[0]
  expect(recordedPeriod(r,'2026-09-01','2026-09-02',s.capturedAt)?.avg).toBe(35)
  s.versions[2].rows.push(row('1区',100))
  expect(realChannelHistories(s,'AU',0.5).find(v=>v.region==='1区')?.current?.price).toBeNull()
 })
 it('uses channel-scoped Canadian zones and preserves missing earlier versions',()=>{
  const s=snapshot();s.versions=s.versions.slice(-1)
  s.versions[0].rows=[{...row('加拿大一区',20),countryCode:'CA',areaName:'加拿大'},{...row('加拿大二区',40),countryCode:'CA',areaName:'加拿大'}]
  const rows=realChannelHistories(s,'CA',0.5)
  expect(rows.find(r=>r.region==='加拿大一区')?.current?.price).toBe(25)
  expect(rows.find(r=>r.region==='加拿大二区')?.current?.price).toBe(45)
  expect(comparedVersions(rows[0],'previousOlder').change).toBeNull()
 })
 it('retains per-piece billing instead of inventing a zero kilogram rate',()=>{
  const s=snapshot();s.versions=s.versions.slice(-1)
  s.versions[0].rows=[{...row('',0),pricingModel:'per-piece-500g',weightToKg:0.5,weightFromInclusive:false,weightToInclusive:true,minChargeWeightKg:0.5,billingStepKg:0.5,intervalPrice:27}]
  expect(realChannelHistories(s,'AU',0.5)[0].current).toMatchObject({price:32,model:'per-piece-500g',intervalPrice:27})
 })
})
