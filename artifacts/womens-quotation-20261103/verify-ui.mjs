/* global fetch, FileReader, Buffer, scrollTo, console, process, URL */
import {chromium} from 'file:///C:/Users/25490/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs';
import {writeFile} from 'node:fs/promises';
const out='C:/Users/25490/Documents/ProjectWorkspaces/quotation/worktrees/womens-quotation-20261103/artifacts/womens-quotation-20261103/';
const base='http://127.0.0.1:5198';
const countries=['美国','英国','德国'];
const rules=['燕文','云途','万邦'].map((carrier,i)=>({id:9910+i,name:'示例服装渠道'+(i+1),billingVerified:true,status:'启用',logisticsChannelId:'demo-'+i,logisticsVersionId:'demo-v1',relations:[{carrier,channel:'服装专线（示例）',channelCode:'DEMO'+i}],prices:countries.map((country,j)=>({areaName:country,countryCode:['US','GB','DE'][j],weightFromKg:0,weightToKg:30,pricingModel:'per-kg',quoteReady:true,minChargeWeightKg:0,startWeightKg:0,firstWeightKg:0,firstWeightPrice:0,nextWeightKg:0,nextWeightPrice:0,pricePerKg:75+i*8+j*5,registrationFee:18+i*2,intervalWeightKg:0,intervalPrice:0,surcharge:0,fuelSurchargeRate:0,volumetric:false,zoneName:'',prohibitedMarks:'',allowedMarks:'',eta:'6-12天'}))}));
const fees={countries:countries.map((country,i)=>({country,fixedFeeUsd:0,selected:true,enabled:true,sortOrder:i})),providers:['燕文','云途','万邦'].map(provider=>({provider,mode:'taxable',selected:true,channels:[]})),updatedAt:'demo'};
const vals={'country-classification':countries.map((country,i)=>({country,enabled:true,stage:'common',sortOrder:i})),'channel-policies':[{id:'普货',category:'普货',enabled:true,countryRules:countries.map(country=>({country,allowedChannels:rules.map(r=>r.id+'::'+r.relations[0].carrier+'::'+r.relations[0].channelCode)}))}],'customer-grades':[{grade:'S',coefficient:1.27,enabled:true}],'exchange-rate':{usdCny:7,eurUsd:1.1,updatedAt:'demo'},'tax-settings':fees,'surcharge-settings':fees,'customer-operation-fees':{customers:[]}};
vals['womens-pricing']={schemaVersion:1,basis:'total-cost-usd',enabled:true,tiers:[{fromUsd:0,toUsd:20,coefficient:1.27},{fromUsd:20,toUsd:30,coefficient:1.25},{fromUsd:30,toUsd:50,coefficient:1.23},{fromUsd:50,toUsd:null,coefficient:1.22}]};
const saved=[];
const finance=Object.fromEntries(Object.entries(vals).map(([k,value])=>[k,{value,_version:1}]));
const product=sku=>({sku,name:sku.endsWith('002')?'女装半裙（示例）':'女装衬衫（示例）',category:'女装',purchasePriceCny:sku.endsWith('002')?58:68,weightG:sku.endsWith('002')?260:220,minOrderQty:10,tier2MinQty:100,tier2PriceCny:sku.endsWith('002')?55:65,tier3MinQty:500,tier3PriceCny:sku.endsWith('002')?52:62,freight10Cny:20,freight100Cny:150,singleFreightCny:3,freeShipping:'否',taxPoint:0,invoiceType:'普票',catalogState:'ready',stockStatus:'有货',dataSource:'standard',factoryInfo:'示例供应商',color:'米白',size:'M',_version:1,_updatedAt:'demo'});
const browser=await chromium.launch({channel:'msedge',headless:true});
const page=await browser.newPage({viewport:{width:1600,height:1100},deviceScaleFactor:1.5,locale:'zh-CN'});
const errors=[],unknown=[],requests=[];page.on('pageerror',e=>errors.push(e.message));
await page.route('**/api/v1/**',async route=>{const req=route.request(),path=new URL(req.url()).pathname.replace('/api/v1','');requests.push(req.method()+' '+path);let data;
if(path==='/auth/me')data={id:'local-demo',account:'DEMO',name:'演示业务员',role:'super_admin',permissions:['quote','purchase','logistics','finance','allRecords','permissions'],mustChangePassword:false};
else if(path==='/auth/csrf')data={headerName:'X-CSRF-TOKEN',token:'local-only'};
else if(path==='/finance-settings')data=finance;
else if(path==='/finance-settings/womens-pricing' && req.method()==='PUT'){const value=req.postDataJSON();if(req.headers()['if-match']!=='1')throw Error('Missing finance version guard');finance['womens-pricing']={value,_version:2};data=finance['womens-pricing'];}
else if(path==='/quotations' && req.method()==='POST'){const payload=req.postDataJSON();data={...payload,id:'demo-'+(saved.length+1),no:'QA-NZ-'+(saved.length+1),createdAt:new Date().toISOString(),_version:0,status:'pending'};saved.push(data);await writeFile(out+payload.quoteMode+'-saved.json',JSON.stringify(data,null,2));}
else if(path==='/quotations/search')data={items:saved,total:saved.length,page:0,size:100,totalPages:1,summary:{pending:saved.length,won:0,lost:0,total:saved.length},countries};
else if(path==='/quotations/review-status')data=saved.map(r=>({...r,_reviewVersion:0,lifecycleState:'active',financeReviewStatus:'pending'}));
else if(path==='/purchase-products/deleted-snapshots')data=[];
else if(path==='/purchase-products')data={content:[],totalElements:0,totalPages:0};
else if(path==='/quotations')data={content:saved,totalElements:saved.length,totalPages:1,size:100,number:0};
else if(path.startsWith('/quotations/demo-'))data=saved.find(r=>r.id===path.split('/')[2]);
else if(path==='/finance-settings/tax-channels'||path==='/finance-settings/freight-discount-channels')data=[];

else if(path==='/quotation-readiness')data={ready:true,purchase:{ready:true},logistics:{ready:true},finance:{ready:true},missing:[]};
else if(path==='/quotation-drafts/mine/state')data={exists:false,version:1};
else if(path==='/quotation-templates'||path==='/personal-quotation-customers')data=[];
else if(path==='/logistics/published/manifest')data={revision:'demo',publishedChannels:3,countries:countries.map((name,i)=>({code:['US','GB','DE'][i],name})),attributes:['普货']};
else if(path==='/logistics/published/rules'||path==='/logistics/published/catalog')data={revision:'demo',rules};
else if(path==='/quotation-sync')data={purchaseVersions:{'NZ-DEMO-001':'1:demo','NZ-DEMO-002':'1:demo'},logisticsRevision:'demo',financeVersions:Object.fromEntries(Object.keys(vals).map(k=>[k,1]))};
else if(path==='/quotation-sync/logistics')data={revision:'demo'};
else if(path.startsWith('/purchase-products/'))data=product(decodeURIComponent(path.split('/').pop()));
else if(path.includes('notification'))data={items:[],unreadCount:0,total:0};
else {unknown.push(path);data=[];}
await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:'OK',data})});});
async function saveNativeSheet(name){const img=page.locator('.sheet-image-scroll img').first();await img.waitFor();const data=await img.evaluate(async el=>{const blob=await(await fetch(el.src)).blob();return await new Promise(resolve=>{const reader=new FileReader();reader.onload=()=>resolve(reader.result.split(',')[1]);reader.readAsDataURL(blob)});});await writeFile(out+name,Buffer.from(data,'base64'));}
try{
await page.goto(base+'/quotation');await page.locator('.mode-field select').waitFor();await page.locator('.mode-field select').selectOption('womens-single');
await page.locator('.customer-field input').fill('女装客户（示例）');
await page.getByPlaceholder('输入 SKU',{exact:true}).fill('NZ-DEMO-001');
await page.getByRole('button',{name:'查询商品',exact:true}).click();
await page.waitForTimeout(1300);
await page.getByRole('button',{name:/快速报价/}).click();
await page.locator('.country-grid button').filter({hasText:'美国'}).first().click();
await page.getByRole('button',{name:'加入报价单',exact:true}).first().waitFor();
await page.getByRole('button',{name:'加入报价单',exact:true}).first().click();
await page.getByRole('button',{name:'加入报价单',exact:true}).first().click();
await page.locator('.sheet-price input').first().waitFor();await page.getByRole('button',{name:'＋ 新增列',exact:true}).click();await page.getByLabel('第 4 个价格列数量',{exact:true}).fill('4');
await page.evaluate(()=>scrollTo(0,0));await page.screenshot({path:out+'actual-single-workbench.png'});
await page.locator('.quote-preview').screenshot({path:out+'actual-single-sheet-editor.png'});
await page.getByRole('button',{name:'预览报价单',exact:true}).click();await saveNativeSheet('actual-single-sheet.png');
await page.getByRole('button',{name:'编辑报价单',exact:true}).click();
await page.locator('.quote-preview button.save').click();await page.waitForTimeout(1500);
if(saved.length!==1)throw Error('Single quote was not saved: '+await page.locator('body').innerText());
// Start an independent bundle.

await page.reload();await page.locator('.mode-field select').waitFor();await page.locator('.mode-field select').selectOption('womens-bundle');
await page.locator('.customer-field input').fill('女装客户（示例）');
await page.getByLabel('第1行 SKU',{exact:true}).fill('NZ-DEMO-001');
if(!await page.getByLabel('第2行 SKU',{exact:true}).count())await page.getByRole('button',{name:'＋ 添加 SKU',exact:true}).click();
await page.getByLabel('第2行 SKU',{exact:true}).fill('NZ-DEMO-002');await page.getByRole('button',{name:'查询全部 SKU',exact:true}).click();await page.waitForTimeout(1000);
await page.getByRole('button',{name:/快速报价/}).click();await page.locator('.country-grid button').filter({hasText:'美国'}).first().click();await page.getByRole('button',{name:'加入报价单',exact:true}).first().waitFor();await page.getByRole('button',{name:'加入报价单',exact:true}).first().click();await page.getByRole('button',{name:'加入报价单',exact:true}).first().click();
await page.locator('.sheet-price input').first().waitFor();await page.getByRole('button',{name:'＋ 新增列',exact:true}).click();await page.getByLabel('第 4 个价格列数量',{exact:true}).fill('4');await page.evaluate(()=>scrollTo(0,0));await page.screenshot({path:out+'actual-bundle-workbench.png'});
await page.locator('.quote-preview').screenshot({path:out+'actual-bundle-sheet-editor.png'});
await page.getByRole('button',{name:'预览报价单',exact:true}).click();await saveNativeSheet('actual-bundle-sheet.png');
await page.getByRole('button',{name:'编辑报价单',exact:true}).click();await page.locator('.quote-preview button.save').click();await page.waitForTimeout(1500);
if(saved.length!==2)throw Error('Bundle quote was not saved: '+await page.locator('body').innerText());
for(const record of saved){
 if(!record.womensPricing || record.customerGrade!=='女装自动系数')throw Error('Missing saved policy');
 for(const option of record.quoteOptions)for(const sample of option.womensPricingSamples){const usd=sample.costCny/record.exchangeRate,expected=usd<20?1.27:usd<30?1.25:usd<50?1.23:1.22;if(sample.coefficient!==expected)throw Error('Incorrect saved band');}
}
await page.goto(base+'/quotation/records');await page.locator('.record-number').filter({hasText:'QA-NZ-1'}).waitFor();await page.locator('.quote-record-table article').filter({hasText:'QA-NZ-1'}).locator('.difference-cell').click();await page.waitForTimeout(700);await page.screenshot({path:out+'actual-record.png',fullPage:true});await page.locator('.womens-trace:visible').first().screenshot({path:out+'actual-record-pricing.png'});await writeFile(out+'record.txt',await page.locator('body').innerText());
await page.goto(base+'/quotation/members');await page.getByText('女装报价系数',{exact:true}).first().click();await page.locator('.womens-settings').waitFor();await page.locator('.womens-settings').screenshot({path:out+'actual-finance-settings.png'});
await page.getByLabel('第1档系数',{exact:true}).fill('1.28');await page.getByRole('button',{name:'预览发布变更',exact:true}).click();await page.getByRole('button',{name:'发布女装系数',exact:true}).click();await page.getByText('已发布 V3',{exact:true}).waitFor();
if(saved[0].womensPricing.tiers[0].coefficient!==1.27)throw Error('History was mutated');
await writeFile(out+'ui-acceptance.json',JSON.stringify({errors,unknown,requests,saved:saved.map(r=>({id:r.id,mode:r.quoteMode,options:r.quoteOptions.length,version:r.womensPricing.version})),published:finance['womens-pricing'],historyPreserved:true},null,2));
console.log(JSON.stringify({errors,unknown,saved:saved.length}));

}catch(e){await page.screenshot({path:out+'actual-failure.png',fullPage:true});await writeFile(out+'actual-failure.txt',await page.locator('body').innerText());console.log(JSON.stringify({error:e.stack,errors,unknown,requests}));process.exitCode=1;}finally{await browser.close();}
