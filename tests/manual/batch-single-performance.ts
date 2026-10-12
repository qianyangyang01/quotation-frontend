// Local-only preview harness. Every API request is fixture-backed; no production connection.
import type { BatchSingleDocument } from '../../src/services/batchSingleQuotation'
if (location.hostname !== '127.0.0.1') throw new Error('Local fixture only')
const rule={id:9910,name:'直接克重',billingVerified:true,status:'启用',logisticsChannelId:'test-channel',logisticsVersionId:'test-version',relations:[{carrier:'燕文',channel:'测试',channelCode:'TEST'}],prices:[{areaName:'美国',countryCode:'US',weightFromKg:0,weightToKg:100,pricingModel:'per-kg',quoteReady:true,minChargeWeightKg:0,startWeightKg:0,firstWeightKg:0,firstWeightPrice:0,nextWeightKg:0,nextWeightPrice:0,pricePerKg:50,registrationFee:5,intervalWeightKg:0,intervalPrice:0,surcharge:0,fuelSurchargeRate:0,volumetric:false,zoneName:'',prohibitedMarks:'',allowedMarks:''}]}
const perfRules=Array.from({length:15},(_,i)=>({...rule,id:9910+i,name:'渠道'+i,logisticsChannelId:'channel-'+i,logisticsVersionId:'version-'+i,relations:[{carrier:'燕文',channel:'测试'+i,channelCode:'TEST'+i}],prices:rule.prices.map(p=>({...p,pricePerKg:50+i}))}));
const fees=(amount:number)=>({countries:[{country:'美国',fixedFeeUsd:amount,selected:true,enabled:true,sortOrder:1}],providers:[{provider:'燕文',mode:'taxable',selected:true,channels:[]}],updatedAt:'test'})
const values={'country-classification':[{country:'美国',enabled:true,stage:'common',sortOrder:1}],'channel-policies':[{id:'普货',category:'普货',enabled:true,countryRules:[{country:'美国',allowedChannels:perfRules.map(r=>r.id+'::燕文::'+r.relations[0]!.channelCode)}]}],'customer-grades':[{grade:'S',coefficient:1.2,enabled:true}],'exchange-rate':{usdCny:6,eurUsd:1.1,updatedAt:'test'},'tax-settings':fees(.3),'surcharge-settings':fees(.2),'customer-operation-fees':{customers:[]}}
const finance=Object.fromEntries(Object.entries(values).map(([key,value])=>[key,{value,_version:1}])),versions=Object.fromEntries(Object.keys(values).map(k=>[k,1]));
versions['freight-discount-settings']=-1;
const saved:BatchSingleDocument[]=[],requests:string[]=[];let fixtureCounter=0;
const originalFetch=window.fetch.bind(window);
window.fetch=async (url,init={})=>{
 const u=new URL(typeof url==='string'?url:url instanceof URL?url.href:url.url,location.href),path=u.pathname.replace('/api/v1','');
 if(!u.pathname.startsWith('/api/v1'))return originalFetch(url,init);
 await new Promise(resolve=>setTimeout(resolve,80)); requests.push((init.method||'GET')+' '+path);let data:unknown, status=200;
 if(path==='/auth/me')data={id:'qa-user',account:'LOCALQA',name:'本地验收',role:'super_admin',permissions:['quote','myRecords','allRecords'],mustChangePassword:false};
 else if(path==='/auth/csrf')data={headerName:'X-CSRF-TOKEN',token:'local-only'};
 else if(path==='/finance-settings')data=finance;
 else if(path==='/quotation-readiness')data={ready:true,purchase:{ready:true},logistics:{ready:true},finance:{ready:true},missing:[]};
 else if(path==='/quotation-drafts/mine/state')data={exists:false};
 else if(path==='/quotation-templates')data=[{id:'test',name:'本地示例模板',ownerAccount:'LOCALQA',ownerName:'本地验收',ownerKey:'ACCOUNT:LOCALQA',createdAt:'2026-10-11',updatedAt:'2026-10-11',items:perfRules.map(r=>({country:'美国',countryCode:'US',channelKey:r.id+'::燕文::'+r.relations[0]!.channelCode,ruleId:r.id,rule:r.name,carrier:'燕文',transport:r.relations[0]!.channel,channelCode:r.relations[0]!.channelCode}))}];
 else if(path==='/personal-quotation-customers')data=[];
 else if(path==='/logistics/published/manifest')data={revision:'performance-14x15',publishedChannels:15,countries:[{code:'US',name:'美国'}],attributes:['普货']};
 else if(path==='/logistics/published/rules'||path==='/logistics/published/catalog')data={revision:'performance-14x15',rules:perfRules};
 else if(path.startsWith('/purchase-products/')){const sku=decodeURIComponent(path.split('/').pop()!);if(sku==='BAD'){status=404;data=null}else{const n=Number(sku.replace('SKU',''))||1;data={sku,skuOrigin:'manual',name:'示例商品'+n,category:'日用品',weightG:sku==='INCOMPLETE'?0:100*n,minOrderQty:1,purchasePriceCny:10*n,singleFreightCny:0,freight10Cny:0,taxPoint:0,invoiceType:'普票',stockStatus:'有货',_version:1,_updatedAt:'2026-10-11T00:00:00Z'}}}
 else if(path==='/quotation-sync')data={purchaseVersions:Object.fromEntries(u.searchParams.getAll('sku').map(s=>[s,'1:2026-10-11T00:00:00Z'])),logisticsRevision:'test',financeVersions:versions};
 else if(path==='/quotation-sync/logistics')data={revision:'performance-14x15'};
 else if(path==='/batch-single-quotations'&&init.method==='POST'){const body=JSON.parse(String(init.body)) as BatchSingleDocument;const record={...body,id:'fixture-'+(++fixtureCounter),no:'LOCAL-BQS-'+fixtureCounter,createdAt:new Date().toISOString(),salespersonName:'本地验收'};data=record;saved.unshift(record)}
 else if(path==='/batch-single-quotations')data={items:saved.map(s=>({...s,skuCount:s.items.length})),total:saved.length};
 else if(path.startsWith('/batch-single-quotations/'))data=saved.find(s=>s.id===path.split('/').pop());
 else if(path.includes('notification'))data={items:[],unreadCount:0,total:0};
 else {status=400;data=null;console.error('Unexpected fixture request',path)}
 return new Response(JSON.stringify({code:status===200?'OK':'FIXTURE_ERROR',message:status===404?'未找到 SKU':'本地验收接口未定义',data}),{status,headers:{'Content-Type':'application/json'}});
};
window.history.replaceState({},'', '/quotation/batch-single');
await import('/src/main.ts');
const info=document.createElement('div');info.style.cssText='position:fixed;bottom:0;left:0;z-index:99999;background:#17232d;color:white;padding:5px 12px;font-size:12px';info.textContent='本地性能模拟 · 14 SKU × 15 渠道 · 每次请求延迟80ms · 不连接生产';document.body.append(info);
(window as Window & {batchFixture?:{saved:BatchSingleDocument[];requests:string[]}}).batchFixture={saved,requests};

