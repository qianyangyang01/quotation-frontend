import assert from 'node:assert/strict'
import { Session } from './live-sync-session.mjs'
import { buildQuotationPayload } from './quotation-payload.mjs'
const purchase=new Session('PERF21'),business=new Session('PERF01'),logistics=new Session('PERF49')
await Promise.all([purchase.login(),business.login(),logistics.login()])
const sku='SYNC-UI-0906', mode=process.argv[2]||'create'
if(mode==='create') {
  const existing=await purchase.request('/purchase-products/'+sku).catch(()=>null)
  await purchase.request('/purchase-products/'+sku,'PUT',{sku,category:'服装',weightG:100,minOrderQty:1,purchasePriceCny:10,singleFreightCny:1,stockStatus:'有货',...(existing?{_version:existing._version}:{})})
  assert.equal((await business.request('/purchase-products/'+sku)).purchasePriceCny,10)
  console.log('PASS: newly created SKU immediately readable by business',sku)
} else if(mode==='edit') {
  const existing=await purchase.request('/purchase-products/'+sku)
  await purchase.request('/purchase-products/'+sku,'PUT',{...existing,purchasePriceCny:12,expectedVersion:existing._version})
  assert.equal((await business.request('/purchase-products/'+sku)).purchasePriceCny,12)
  console.log('PASS: current SKU updated to 12')
} else if(mode==='checks') {
  await assert.rejects(()=>purchase.request('/quotation-sync?sku='+sku),/403/)
  await assert.rejects(()=>logistics.request('/quotation-sync?sku='+sku),/403/)
  await assert.rejects(()=>business.request('/purchase-products/'+sku,'PUT',{sku}),/403/)
  await assert.rejects(()=>business.request('/quotation-sync?sku='+encodeURIComponent('invalid!')),/422/)
  await assert.rejects(()=>business.request('/quotation-sync?'+Array.from({length:101},()=> 'sku=A').join('&')),/422/)
  const body=buildQuotationPayload('PERF01',0,false)
  body.primarySku=sku
  body.purchaseVersions=(await business.request('/quotation-sync?sku='+sku)).purchaseVersions
  body.logisticsSyncScope='selected'
  const option=body.quoteOptions[0]
  option.logisticsSamples=[{input:option.logisticsInput,total:option.freightCny,etaMinDays:6,etaMaxDays:12}]
  const before=JSON.stringify(body.purchaseVersions)
  const extra='SYNC-UNRELATED-'+Date.now()
  await purchase.request('/purchase-products/'+extra,'PUT',{sku:extra,category:'服装',weightG:100,minOrderQty:1,purchasePriceCny:10,singleFreightCny:1,stockStatus:'有货'})
  assert.equal(JSON.stringify((await business.request('/quotation-sync?sku='+sku)).purchaseVersions),before)
  await business.request('/quotations','POST',body)
  const old=await purchase.request('/purchase-products/'+sku)
  await purchase.request('/purchase-products/'+sku,'PUT',{...old,purchasePriceCny:13,expectedVersion:old._version})
  await assert.rejects(()=>business.request('/quotations','POST',body),/409/)
  console.log('PASS: role boundaries, invalid/oversized probes, unrelated additions do not block saving; current SKU edit rejects stale save')
}
