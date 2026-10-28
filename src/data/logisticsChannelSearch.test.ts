import {describe,it,expect} from 'vitest'
import {fuzzyChannelScore,channelSearchTerms,searchCountryChannels} from './logisticsChannelSearch'
import {majorChannelHistories} from './logisticsMajorChannelHistory'
import mapping from './logisticsPriceWatchlistBindings.json'
import type {RealPriceSnapshot,HistoryChannel} from './logisticsRealPriceHistory'
import type {LogisticsPriceRow} from './logistics'
function snapshot():RealPriceSnapshot {
 const listed=mapping.bindings['云途普货CHC']
 const channels:HistoryChannel[]=[{...listed,ruleId:1,code:'CHC',current:'v1',attribute:'普货',enabled:true,quoteReady:true},
 {id:'other',name:'云途全球特惠带电专线',provider:'云途',ruleId:2,code:'OTHER-D',current:'v2',attribute:'带电',enabled:true,quoteReady:true},
 {id:'us-only',name:'云途美国专线',provider:'云途',ruleId:3,code:'US-D',current:'v3',attribute:'带电',enabled:true,quoteReady:true}]
 return {dataset:'test',capturedAt:'2026-10-28T01:00:00Z',channels,versions:channels.map(c=>({id:c.current,channel:c.id,number:1,status:'published',publishedAt:'2026-10-01T00:00:00Z',fileName:'source',rows:[{countryCode:c.id==='us-only'?'US':'DE',areaName:'',zoneName:'',pricePerKg:40,registrationFee:10,weightFromKg:0,weightToKg:30,minChargeWeightKg:0,startWeightKg:0,firstWeightKg:0,firstWeightPrice:0,nextWeightKg:0,nextWeightPrice:0,intervalPrice:0,surcharge:0,pricingModel:'per-kg'} as LogisticsPriceRow]}))}
}
describe('fuzzy search outside the main-channel shortlist',()=>{
 it('matches partial Chinese names even with words in between, and normalizes punctuation and case',()=>{
  expect(fuzzyChannelScore('云途普货',['云途欧洲专线（特惠普货）-CHC'])).not.toBeNull()
  expect(fuzzyChannelScore('ｃｈｃ',['云途欧洲专线（特惠普货）-CHC'])).not.toBeNull()
  expect(fuzzyChannelScore('O T H E R - D',['OTHER-D'])).not.toBeNull()
  expect(fuzzyChannelScore('燕文',['云途欧洲专线'])).toBeNull()
 })
 it('matches multiple terms across provider and region, in any order',()=>{
  expect(fuzzyChannelScore('2区 云途',['云途','澳大利亚2区'])).not.toBeNull()
  expect(fuzzyChannelScore('3区 云途',['云途','澳大利亚2区'])).toBeNull()
  expect(channelSearchTerms(' ， -- ')).toEqual([])
 })
 it('keeps the default shortlist but searches other attributes and routes within the selected country',()=>{
  const s=snapshot()
  expect(majorChannelHistories(s,'DE',.5,'普货/服装').rows.map(r=>r.channel.id)).toEqual([mapping.bindings['云途普货CHC'].id])
  const found=searchCountryChannels(s,'DE',.5,'云途')
  expect(found.map(r=>r.channel.id)).toEqual([mapping.bindings['云途普货CHC'].id,'other'])
  expect(found[0].isMajor).toBe(true)
  expect(found[1]).toMatchObject({isMajor:false,tableAttribute:'带电',temporaryEquivalent:false})
  expect(found[1].current?.price).toBe(30)
 })
 it('does not expand on empty input, include disabled channels, or borrow aliases for renamed identities',()=>{
  const s=snapshot()
  expect(searchCountryChannels(s,'DE',.5,' ')).toEqual([])
  s.channels[1].enabled=false
  expect(searchCountryChannels(s,'DE',.5,'全球')).toEqual([])
  s.channels[0].name='新渠道'
  expect(searchCountryChannels(s,'DE',.5,'云途普货')).toEqual([])
 })
})
