import { afterEach, expect, it } from 'vitest'
import aliases from '../../backend/src/main/resources/country-aliases.json'
import { countryIdentity, sameCountryIdentity } from './countryIdentity'
import { calculateLogisticsFee, replaceLogisticsRules, type LogisticsRule } from './logistics'
import { channelsAvailableForCountry, financeAllowsLogisticsChannel, type FinanceChannelPolicy } from './financeChannelPolicies'
import { isEuCountry, sameTaxCountry } from './europeanUnion'
afterEach(() => replaceLogisticsRules([]))
it.each(aliases)('keeps country %s aliases aligned across finance, indexed pricing and saved draft names', (code,name,alias) => {
  const relation={carrier:'物流商',channel:'渠道',channelCode:'C1',discounts:''}
  const rule={id:1,name:'渠道',status:'启用',billingVerified:true,relations:[relation],prices:[{countryCode:code,areaName:alias,weightFromKg:0,weightToKg:2,weightToInclusive:true,pricePerKg:50,registrationFee:10,quoteReady:true}]} as LogisticsRule
  replaceLogisticsRules([rule])
  const policies=[{id:'服装',category:'服装',enabled:true,updatedAt:'test',countryRules:[{country:name,allowedChannels:['1::物流商::C1'],stage:'standard',continent:'亚洲',sortOrder:1}]}] as FinanceChannelPolicy[]
  for(const country of [code,name,alias]) {
    expect(countryIdentity(country)).toBe(code)
    expect(channelsAvailableForCountry(country)).toHaveLength(1)
    expect(financeAllowsLogisticsChannel(policies,'服装',country,1,relation)).toBe(true)
    expect(financeAllowsLogisticsChannel(policies,'普货',country,1,relation)).toBe(false)
    expect(calculateLogisticsFee(rule,country,.5,['服装'])?.total).toBe(35)
    expect(sameTaxCountry(name,country)).toBe(true)
  }
  expect(sameCountryIdentity(name,'美国')).toBe(false)
  expect(rule.prices[0]!.areaName).toBe(alias)
})
it('retains EU membership for Latvia aliases without including UAE',()=>{
  expect(isEuCountry('拉托维亚')).toBe(true)
  expect(isEuCountry('阿联酋')).toBe(false)
})
