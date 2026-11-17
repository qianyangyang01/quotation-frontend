import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { chromium } from 'file:///C:/Users/25490/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs'
const base=process.env.QA_BASE_URL || 'http://127.0.0.1:5197', out='outputs/quantity-sort-preview'
assert.equal(new URL(base).hostname,'127.0.0.1','QA must stay local')
await mkdir(out,{recursive:true})
const definitions=[['帆之润','帆之润美国专线小包',2,40,1,'7 ~ 15 天'],['容鼎','美国纯商派DDP专线-普货',5,30,15,'8 ~ 12 天'],['递四方','联邮标准商派专线-普货（S5246）',30,18,40,'7 ~ 12 天'],['递四方','联邮标准挂号-普货（QC）',3,45,5,'7 ~ 12 天'],['递四方','联邮经济挂号-普货（Q5）',30,55,10,'9 ~ 14 天'],['云途','云途全球专线挂号（标准）',.8,22,10,'5 ~ 8 天']]
const rules=definitions.map(([carrier,name,max,rate,fee,eta],i)=>({id:9910+i,name,billingVerified:true,status:'启用',logisticsChannelId:'local-preview-'+i,logisticsVersionId:'local-preview-version',relations:[{carrier,channel:name,channelCode:'PREVIEW'+i}],prices:[{areaName:'美国',countryCode:'US',weightFromKg:0,weightToKg:max,pricingModel:'per-kg',quoteReady:true,minChargeWeightKg:.05,startWeightKg:0,firstWeightKg:0,firstWeightPrice:0,nextWeightKg:0,nextWeightPrice:0,pricePerKg:rate,registrationFee:fee,intervalWeightKg:0,intervalPrice:0,surcharge:0,fuelSurchargeRate:0,volumetric:false,zoneName:'',prohibitedMarks:'',allowedMarks:'',estimatedTime:eta,deliveryTime:eta,timeliness:eta}]}))
rules.forEach((rule,i)=>{const days=definitions[i][5].match(/\d+/g).map(Number);Object.assign(rule.prices[0],{etaMinDays:days[0],etaMaxDays:days[1],etaStatus:'ready'})})
const fees={countries:[{country:'美国',fixedFeeUsd:0,selected:true,enabled:true,sortOrder:1}],providers:[...new Set(definitions.map(x=>x[0]))].map(provider=>({provider,mode:'taxable',selected:true,channels:[]})),updatedAt:'local-preview'}
const values={'country-classification':[{country:'美国',enabled:true,stage:'common',sortOrder:1}],'channel-policies':[{id:'普货',category:'普货',enabled:true,countryRules:[{country:'美国',allowedChannels:rules.map(rule=>`${rule.id}::${rule.relations[0].carrier}::${rule.relations[0].channelCode}`)}]}],'customer-grades':[{grade:'S',coefficient:1.2,enabled:true}],'exchange-rate':{usdCny:6.63,eurUsd:1.1,updatedAt:'local-preview'},'tax-settings':fees,'surcharge-settings':fees,'customer-operation-fees':{customers:[]}}
const finance=Object.fromEntries(Object.entries(values).map(([key,value])=>[key,{value,_version:1}]))
const versions=Object.fromEntries(Object.keys(values).map(k=>[k,1]))
await writeFile(out+'/fixture.json',JSON.stringify({rules,finance,versions}))
const errors=[],requests=[],checks=[],saved=[]
const browser=await chromium.launch({channel:'msedge',headless:true})
try{
 const context=await browser.newContext({viewport:{width:1920,height:1500},deviceScaleFactor:1,locale:'zh-CN'})
 await context.addInitScript(()=>{Object.defineProperty(navigator,'clipboard',{value:{writeText:async text=>{window.__copiedText=text}}})})
 const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message))
 await page.route('**/api/v1/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname.replace('/api/v1','');requests.push(req.method()+' '+path)
  let data
  if(path==='/auth/me')data={id:'local-preview-user',account:'LOCALPREVIEW',name:'本地预览',role:'super_admin',permissions:['quote','purchase','logistics','finance','allRecords','permissions'],mustChangePassword:false}
  else if(path==='/auth/csrf')data={headerName:'X-CSRF-TOKEN',token:'local-only'}
  else if(path==='/finance-settings')data=finance
  else if(path==='/quotation-readiness')data={ready:false,purchase:{ready:false},logistics:{ready:true},finance:{ready:true},missing:['无采购商品']}
  else if(path==='/quotation-drafts/mine/state')data={exists:false}
  else if(path==='/quotation-templates'||path==='/personal-quotation-customers')data=[]
  else if(path==='/logistics/published/manifest')data={revision:'local-preview',publishedChannels:rules.length,countries:[{code:'US',name:'美国'}],attributes:['普货']}
  else if(path==='/logistics/published/rules'||path==='/logistics/published/catalog')data={revision:'local-preview',rules}
  else if(path==='/quotation-sync')data={purchaseVersions:{},logisticsRevision:'local-preview',financeVersions:versions}
  else if(path==='/quotation-sync/logistics')data={revision:'local-preview'}
  else if(path.includes('notification'))data={items:[],unreadCount:0,total:0}
  else if(path==='/quotations'&&req.method()==='POST'){const body=req.postDataJSON();saved.push(body);data={...body,id:'local-save-'+saved.length,no:'LOCAL-QA',createdAt:new Date().toISOString()}}
  else if(req.method()!=='GET'){errors.push('Blocked mutation '+req.method()+' '+path);return route.fulfill({status:403,body:'Preview read-only'})}
  else data=[]
  await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:'OK',data})})
 })
 await page.goto(base+'/quotation');await page.locator('.mode-field select').waitFor()
 await page.locator('.customer-field input').fill('本地方案预览 · 样例运价')
 await page.locator('.mode-field select').selectOption('shipping-only')
 await page.getByLabel('手填重量',{exact:true}).fill('200')
 await page.getByRole('button',{name:'查询代发报价',exact:true}).click()
 await page.getByRole('button',{name:/快速报价/}).click()
 const matrix=page.locator('.common-matrix');await matrix.locator('.selection-actions button').first().waitFor()
 await page.locator('.toast').waitFor({state:'hidden'})
 await matrix.locator('footer select').selectOption('10')
 for(const width of [1280,1440,1920]){
  await page.setViewportSize({width,height:1500})
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,'Unexpected page overflow at '+width)
 }
 checks.push('desktop layouts at 1280/1440/1920 have no document overflow')
 assert.equal(await page.getByLabel('比价数量',{exact:true}).inputValue(),'1, 2, 3')
 checks.push('defaults to 1,2,3')
 await matrix.screenshot({path:out+'/system-matrix-default-1-2-3.png'})
 await page.getByLabel('比价数量',{exact:true}).fill('4, 8, 12')
 await page.getByRole('button',{name:'应用数量',exact:true}).click()
 await page.getByLabel('排序数量',{exact:true}).selectOption('8')
 await page.mouse.move(0,0)
 await page.waitForTimeout(300)
 await matrix.screenshot({path:out+'/system-matrix-4-8-12.png'})
 assert.deepEqual(await matrix.locator('.table-head>button').allTextContents(),['4件总报价 ↕USD / CNY · 点击排序','8件总报价 ↑USD / CNY · 点击排序','12件总报价 ↕USD / CNY · 点击排序'])
 checks.push('4,8,12 dynamic columns and selected 8 quantity')
 await matrix.getByRole('button',{name:'▦ 复制当前国家',exact:true}).click()
 const copied=await page.evaluate(()=>window.__copiedText)
 assert.ok(copied.split('\r\n')[0].includes('4件（USD）'))
 assert.ok(copied.split('\r\n')[0].includes('12件（USD）'))
 assert.ok(!copied.split('\r\n')[0].includes('1件（USD）'));checks.push('clipboard contains custom quantities and displayed prices')
 const prices=await matrix.locator('.quote-rows article .comparison-price:nth-child(4)>b').allTextContents()
 const numeric=prices.filter(p=>p.includes('$')).map(p=>Number(p.match(/\$([\d.]+)/)[1]))
 assert.deepEqual(numeric,[...numeric].sort((a,b)=>a-b));checks.push('8 quantity ascending sort')
 assert.ok(await matrix.locator('.comparison-overweight').count()>0);checks.push('per-quantity overweight shown')
 await page.getByLabel('排序数量',{exact:true}).selectOption('12')
 await matrix.screenshot({path:out+'/system-matrix-sort-12.png'})
 const count=await matrix.locator('.quote-rows article').count()
 await page.getByLabel('只看 12件可报价').check()
 assert.ok(await matrix.locator('.quote-rows article').count()<count);checks.push('12 quantity availability filter')
 await page.getByLabel('只看 12件可报价').uncheck()
 await page.getByLabel('比价数量',{exact:true}).fill('5, 10, 20, 30')
 await page.getByRole('button',{name:'应用数量',exact:true}).click()
 assert.match(await matrix.locator('[role="alert"]').innerText(),/最多输入 3 个数量/)
 assert.equal(await matrix.locator('.table-head>button').count(),3)
 assert.match(await matrix.locator('.table-head>button').first().innerText(),/4件/)
 checks.push('four quantities rejected without changing existing three columns')
 await matrix.screenshot({path:out+'/system-matrix-max-three.png'})
 await page.getByLabel('比价数量',{exact:true}).fill('5, 10, 20')
 await page.getByRole('button',{name:'应用数量',exact:true}).click()
 await page.getByLabel('排序数量',{exact:true}).selectOption('20')
 await page.mouse.move(0,0)
 await page.waitForTimeout(300)
 assert.equal(await matrix.locator('.table-head>button').count(),3);checks.push('5,10,20 custom three-column combination')
 await matrix.screenshot({path:out+'/system-matrix-5-10-20.png'})
 await page.getByLabel('比价数量',{exact:true}).fill('4, 8, 12')
 await page.getByRole('button',{name:'应用数量',exact:true}).click()
 await page.getByLabel('排序数量',{exact:true}).selectOption('8')
 await page.evaluate(()=>window.scrollTo(0,0));await page.screenshot({path:out+'/system-page.png',fullPage:true})
 const selectedKeys=[]
 for(let index=0;index<2;index++){
  const article=matrix.locator('.quote-rows article').nth(index)
  selectedKeys.push((await article.innerText()).match(/PREVIEW\d+/)[0])
  await article.locator('.selection-actions>button').first().click()
 }
 const sheetQuantities=page.locator('.sheet-quantity input')
 await sheetQuantities.nth(0).fill('2')
 await sheetQuantities.nth(1).fill('5')
 await sheetQuantities.nth(2).fill('7')
 const customerPrice=page.locator('.sheet-price input').first()
 await customerPrice.fill('123.45');await customerPrice.blur()
 assert.deepEqual(await page.getByLabel('比价数量',{exact:true}).inputValue(),'4, 8, 12')
 assert.match(await matrix.locator('.table-head>button').first().innerText(),/4件/)
 checks.push('editing quote-sheet quantities to 2,5,7 does not change upper comparison 4,8,12')
 await page.getByLabel('比价数量',{exact:true}).fill('1,2,3')
 await page.getByRole('button',{name:'应用数量',exact:true}).click()
 await page.getByLabel('排序数量',{exact:true}).selectOption('3')
 await page.getByLabel('搜索物流渠道',{exact:true}).fill('云途')
 assert.deepEqual(await sheetQuantities.evaluateAll(inputs=>inputs.map(input=>input.value)),['2','5','7'])
 assert.equal(await customerPrice.inputValue(),'123.45')
 checks.push('upper comparison/sort/filter changes preserve quote-sheet quantities and manual customer price')
 await page.getByRole('button',{name:'＋ 新增列',exact:true}).click()
 await sheetQuantities.last().fill('9')
 assert.deepEqual(await sheetQuantities.evaluateAll(inputs=>inputs.map(input=>input.value)),['2','5','7','9'])
 assert.equal(await matrix.locator('.table-head>button').count(),3)
 checks.push('comparison three-value cap does not restrict the quote-sheet existing fourth custom column')
 await page.locator('.quote-preview').screenshot({path:out+'/system-sheet-quantity-isolation.png'})
 await page.getByRole('button',{name:/保存 1 张报价单/}).click()
 await page.waitForFunction(()=>document.body.innerText.includes('报价已保存'))
 assert.equal(saved.length,1)
 assert.deepEqual(saved[0].quoteOptions.map(option=>option.channelCode).sort(),selectedKeys.sort())
 assert.equal(saved[0].customerQuote.rows.length,2)
 assert.deepEqual(saved[0].customerQuote.quantities,[2,5,7,9])
 assert.deepEqual(saved[0].systemQuantityQuotes.quantities,[2,5,7,9])
 assert.equal(saved[0].customerQuote.rows[0].prices[0],123.45)
 assert.notEqual(saved[0].systemQuantityQuotes.rows[0].prices[0],123.45)
 checks.push('saved quantity and customer-price snapshots use the quote sheet, independent of comparison quantities')
 checks.push('saving includes only two explicitly added channels after quantity/sort/filter changes')
 await writeFile(out+'/saved-selected-channels.json',JSON.stringify(saved,null,2))
 assert.deepEqual(errors,[])
 await writeFile(out+'/checks.json',JSON.stringify({checks,errors,requests,data:'Local synthetic rate fixtures using real application and quotation calculator; no production requests'},null,2))
 console.log(JSON.stringify({checks,errors,out}))
}catch(e){console.error(e);process.exitCode=1;await writeFile(out+'/failure.json',JSON.stringify({message:e.message,errors,requests},null,2))}finally{await browser.close()}
