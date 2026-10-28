import {expect,it} from 'vitest'
import {assertPriceSnapshot} from './logisticsPriceSnapshotValidation'
import {existsSync,readFileSync} from 'node:fs'

function sample(){return {capturedAt:'2026-10-28T01:00:00Z',channels:[{id:'c',name:'真实渠道',provider:'物流商',attribute:'普货',code:'code',enabled:true,quoteReady:true,current:'v'}],versions:[{id:'v',channel:'c',number:1,status:'published',publishedAt:'2026-10-01T01:00:00Z',rows:[],preparedPrices:{'DE|1':{'全国统一':{price:20,rate:15,fee:5,chargeWeight:1,intervalPrice:null,model:'per-kg',reason:''}}}}]}}
it.each([NaN,Infinity,-1,'20',undefined])('rejects an invalid computed price without modifying the source: %s',value=>{
 const data=sample();Object.assign(data.versions[0]!.preparedPrices['DE|1']['全国统一'],{price:value})
 const original=JSON.stringify(data);expect(()=>assertPriceSnapshot(data)).toThrow('数据有误');expect(JSON.stringify(data)).toBe(original)
})
it('rejects duplicated version identity and a missing current version',()=>{
 const data=sample();data.versions.push({...data.versions[0]!});expect(()=>assertPriceSnapshot(data)).toThrow()
 data.versions=[];expect(()=>assertPriceSnapshot(data)).toThrow()
})
it('accepts unavailable prices and existing local source/prepared fixtures without rewriting them',()=>{
 const data=sample();Object.assign(data.versions[0]!.preparedPrices['DE|1']['全国统一'],{price:null,reason:'此版本缺价'})
 expect(()=>assertPriceSnapshot(data)).not.toThrow()
 for(const file of ['real-history.json','prepared-history.json']){
  const path='outputs/local-logistics-price-preview/'+file
  if(existsSync(path)){const raw=readFileSync(path,'utf8');const snapshot=JSON.parse(raw);expect(()=>assertPriceSnapshot(snapshot)).not.toThrow();expect(JSON.stringify(snapshot)).toBe(JSON.stringify(JSON.parse(raw)))}
 }
})
