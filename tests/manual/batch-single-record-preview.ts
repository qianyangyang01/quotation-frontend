import { createApp } from 'vue'
import Detail from '../../src/components/quotation/BatchSingleRecordDetail.vue'
import { setPricingTestPermissions } from '../../src/test/pricingPermissions'
import type { BatchSingleDocument } from '../../src/services/batchSingleQuotation'
if (location.hostname !== '127.0.0.1') throw new Error('Local fixture only')
window.fetch = async () => { throw new Error('Offline layout fixture') }
setPricingTestPermissions(['purchase','finance'])
const items=Array.from({length:21},(_,i)=>({input:{primarySku:`DEMO-${String(i+1).padStart(3,'0')}`,productSummary:'示例家居商品',purchaseUnitPriceCny:8.3+i,exchangeRate:6.7},sheet:{rows:['US','AU','GB'].map((country,j)=>({key:String(j),sku:`DEMO-${String(i+1).padStart(3,'0')}`,number:j+1,country,region:country==='AU'?'Zone 3':'',provider:'4PX',shippingTime:'7-12 working days',sourceDescription:'示例渠道 · 普货专线',prices:[12.3+i+j,22.5+i+j,33.7+i+j]}))}}))
const record={id:'local',no:'BQS-LOCAL-DEMO',createdAt:'2026-10-11T12:00:00Z',salespersonName:'示例报价员',customerName:'示例客户（本地验收）',items,sheet:{agent:'示例报价员',date:'11 Oct 2026',quantityLabels:['1 pc','2 pcs','3 pcs'],issues:[],rows:items.flatMap(i=>i.sheet.rows)}} as unknown as BatchSingleDocument
createApp(Detail,{document:record}).mount('#app')
