import assert from 'node:assert/strict'
import {mkdir,writeFile} from 'node:fs/promises'
import {pathToFileURL} from 'node:url'
import {Session,base,priceSnapshot} from './client.mjs'
const {chromium}=await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE||'C:/Users/25490/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs').href)
const out='artifacts/performance/browser-linked-pricing'
await mkdir(out,{recursive:true})
const employee=new Session('LOAD030'),purchase=new Session('PERFPUR'),finance=new Session('PERFFIN'),admin=new Session('PERFADMIN')
for(const s of [employee,purchase,finance,admin])await s.login()
const sku='QA-LINK-PRICE-'+Date.now(),customer='QA-UI-'+Date.now(),checks=[]
const source=await purchase.request('/purchase-products/BK2601990')
const initial={...source,sku,purchasePriceCny:25,taxPoint:0,weightG:450,singleFreightCny:1.6,freight10Cny:16,freight100Cny:150,tier2MinQty:100,tier2PriceCny:20,tier3MinQty:500,tier3PriceCny:18,freeShipping:'否',dataSource:'standard',catalogState:'ready',minOrderQty:1}
for(const key of ['id','_version','_updatedAt','createdAt','updatedAt'])delete initial[key]
await purchase.request('/purchase-products/'+sku,{method:'PUT',body:initial})
const state=await employee.request('/quotation-drafts/mine/state')
await employee.request('/quotation-drafts/mine/state',{method:'PUT',headers:{'If-Match':String(state.version)},body:{schemaVersion:2,customerName:customer,skuSearch:sku,quoteMode:'single',logisticsAttribute:'普货',selectedCustomerGrade:'S',monthlySalesEstimate:'10',commissionThreshold:1,specialPackagingGrams:0,quoteMatrixMode:'common',product:{sku,quantity:1,purchaseInvoiceTaxApplied:true,weightSource:'purchase'},commonSelections:[]}})
const browser=await chromium.launch({channel:'chrome',headless:true})
const context=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'}),page=await context.newPage()
const pageErrors=[];page.on('pageerror',e=>pageErrors.push(e.message))
let financeRestore,error
async function numberInput(label,value){await page.waitForFunction(({label,value})=>{const target=[...document.querySelectorAll('.cost-metrics label')].find(e=>e.textContent.includes(label))?.querySelector('input');return target&&Math.abs(Number(target.value)-value)<.00001},{label,value},{timeout:30000})}
async function save(){
 const response=page.waitForResponse(r=>r.url()===base+'/api/v1/quotations'&&r.request().method()==='POST',{timeout:30000})
 await page.locator('.quote-preview button.save').click()
 const http=await response,payload=await http.json();assert.equal(http.status(),200,JSON.stringify(payload));return payload.data
}
async function openNextQuote(unitCost){
 await page.waitForFunction(()=>document.querySelector('[aria-label="客户名称"]')?.value==='')
 await page.getByRole('combobox',{name:'客户名称',exact:true}).fill(customer)
 await page.getByPlaceholder('输入 SKU',{exact:true}).fill(sku)
 await page.locator('[data-validation-field="logisticsAttribute"] select').selectOption('普货')
 await page.getByRole('button',{name:'查询商品',exact:true}).click()
 await numberInput('计入成本单价',unitCost)
 await page.getByRole('textbox',{name:'特殊包装克重',exact:true}).fill('0')
 await page.locator('.country-grid button').filter({hasText:'美国'}).first().click()
 await page.getByRole('searchbox',{name:'搜索物流渠道',exact:true}).fill('闪电猴')
 await page.locator('.common-matrix .quote-rows article').filter({hasText:'C-eec0050232e84858bc6a'}).getByRole('button',{name:'加入报价单',exact:true}).click()
}
try{
 await page.goto(base+'/login');await page.getByPlaceholder('请输入账号',{exact:true}).fill(employee.account);await page.getByPlaceholder('请输入密码',{exact:true}).fill('PerfAdmin123!');await page.getByRole('button',{name:'登录系统'}).click()
 await numberInput('计入成本单价',25.25);await numberInput('商品重量',450);await numberInput('国内运费',1.6)
 await page.locator('.country-grid button').filter({hasText:'美国'}).first().click()
 await page.getByRole('searchbox',{name:'搜索物流渠道',exact:true}).fill('闪电猴')
 const row=page.locator('.common-matrix .quote-rows article').filter({hasText:'C-eec0050232e84858bc6a'})
 await row.getByRole('button',{name:'加入报价单',exact:true}).click()
 const original=await save(),originalPrices=priceSnapshot(original)
 assert.equal(original.purchaseUnitPriceCny,25.25)
 assert.equal(original.quoteOptions[0].freightCny,43.54)
 assert.equal(original.totalCostCny,70.39)
 checks.push({name:'Browser-calculated original purchase, packaging, freight and total independently match',passed:true,id:original.id})
 await openNextQuote(25.25)
 const before=await purchase.request('/purchase-products/'+sku)
 await purchase.request('/purchase-products/'+sku+'/maintenance',{method:'POST',body:{...before,purchasePriceCny:30,taxPoint:.02,weightG:550,singleFreightCny:2.5,freight10Cny:25}})
 await page.locator('.live-data-notice').filter({hasText:'更新'}).waitFor({timeout:30000})
 assert(await page.locator('.quote-preview button.save').isDisabled())
 await page.locator('.live-data-notice').getByRole('button',{name:'更新报价',exact:true}).click()
 await numberInput('计入成本单价',30.6);await numberInput('商品重量',550);await numberInput('国内运费',2.5)
 const fresh=await save()
 assert.equal(fresh.purchaseUnitPriceCny,30.6)
 assert.equal(fresh.quoteOptions[0].freightCny,49.66)
 assert.equal(fresh.totalCostCny,82.76)
 assert.equal(fresh.weightSnapshot.quantities.find(v=>v.quantity===1).weightKg,.561)
 assert.equal(priceSnapshot(await employee.request('/quotations/'+original.id)),originalPrices)
 assert.deepEqual(await employee.request('/quotations/'+fresh.id),await admin.request('/quotations/'+fresh.id))
 checks.push({name:'Purchase update blocks the old page, refresh recalculates tax/weight/freight/cost, saved history is unchanged',passed:true,id:fresh.id})
 await openNextQuote(30.6)
 await page.locator('[data-validation-field="monthlySalesEstimate"] select').selectOption('100')
 // Current business rule uses the ten-piece freight per unit for standard data,
 // independently of the selected purchase price tier.
 await numberInput('计入成本单价',20.4);await numberInput('国内运费',2.5)
 const tierQuote=await save()
 assert.equal(tierQuote.purchaseUnitPriceCny,20.4);assert.equal(tierQuote.totalCostCny,72.56)
 assert.equal(tierQuote.quoteOptions[0].freightCny,49.66)
 assert.equal(priceSnapshot(await employee.request('/quotations/'+fresh.id)),priceSnapshot(fresh))
 checks.push({name:'Selected purchase tier and quantity-based domestic freight reach the saved quote without changing history',passed:true,id:tierQuote.id})
 await openNextQuote(30.6)
 const setting=await finance.request('/finance-settings/exchange-rate')
 const updated=await finance.request('/finance-settings/exchange-rate',{method:'PUT',headers:{'If-Match':String(setting._version)},body:{...setting.value,usdCny:6.8}})
 financeRestore={version:updated._version,value:setting.value}
 await page.locator('.live-data-notice').filter({hasText:'更新'}).waitFor({timeout:30000})
 assert(await page.locator('.quote-preview button.save').isDisabled())
 await page.locator('.live-data-notice').getByRole('button',{name:'更新报价',exact:true}).click()
 await page.locator('.live-data-notice').waitFor({state:'hidden',timeout:30000})
 const converted=await save()
 assert.equal(converted.exchangeRate,6.8);assert.equal(converted.totalCostCny,82.76)
 assert.equal(converted.systemQuoteCny,Math.round(converted.systemQuoteUsd*680)/100)
 assert.notEqual(converted.systemQuoteUsd,fresh.systemQuoteUsd)
 assert.equal(priceSnapshot(await employee.request('/quotations/'+fresh.id)),priceSnapshot(fresh))
 const claimed=await finance.request('/quotations/'+converted.id+'/finance-review',{method:'PATCH',body:{action:'claim',_version:converted._version,_reviewVersion:converted._reviewVersion}})
 const completed=await finance.request('/quotations/'+converted.id+'/finance-review',{method:'PATCH',body:{action:'complete',_version:claimed._version,_reviewVersion:claimed._reviewVersion,financeReviewStatus:'approved'}})
 assert.equal(priceSnapshot(completed),priceSnapshot(converted));assert.deepEqual(await employee.request('/quotations/'+converted.id),await admin.request('/quotations/'+converted.id))
 checks.push({name:'Finance exchange change reaches the open page, new quote uses new rate, review and other roles preserve exact saved prices',passed:true,id:converted.id})
 await page.screenshot({path:out+'/linked-pricing.png',fullPage:true})
 assert.deepEqual(pageErrors,[])
}catch(e){error=e.stack;await page.screenshot({path:out+'/failure.png',fullPage:true});await writeFile(out+'/failure.txt',await page.locator('body').innerText())}
finally{
 if(financeRestore)await finance.request('/finance-settings/exchange-rate',{method:'PUT',headers:{'If-Match':String(financeRestore.version)},body:financeRestore.value})
 await browser.close()
}
const report={base,sku,checks,pageErrors,error,passed:!error&&checks.length===4}
await writeFile(out+'/report.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report));if(!report.passed)process.exitCode=1
