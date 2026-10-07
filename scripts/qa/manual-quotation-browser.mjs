import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'
const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE || 'C:/Users/25490/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs').href)
const out='artifacts/manual-quotation', base=process.env.QA_BASE_URL || 'http://127.0.0.1:5188'
assert(new URL(base).hostname==='127.0.0.1','QA must run against loopback only')
await mkdir(out,{recursive:true})
const rule={id:9910,name:'直接克重',billingVerified:true,status:'启用',logisticsChannelId:'test-channel',logisticsVersionId:'test-version',relations:[{carrier:'燕文',channel:'测试',channelCode:'TEST'}],prices:[{areaName:'美国',countryCode:'US',weightFromKg:0,weightToKg:100,pricingModel:'per-kg',quoteReady:true,minChargeWeightKg:0,startWeightKg:0,firstWeightKg:0,firstWeightPrice:0,nextWeightKg:0,nextWeightPrice:0,pricePerKg:50,registrationFee:5,intervalWeightKg:0,intervalPrice:0,surcharge:0,fuelSurchargeRate:0,volumetric:false,zoneName:'',prohibitedMarks:'',allowedMarks:''}]}
const fees=amount=>({countries:[{country:'美国',fixedFeeUsd:amount,selected:true,enabled:true,sortOrder:1}],providers:[{provider:'燕文',mode:'taxable',selected:true,channels:[]}],updatedAt:'test'})
const values={'country-classification':[{country:'美国',enabled:true,stage:'common',sortOrder:1}],'channel-policies':[{id:'普货',category:'普货',enabled:true,countryRules:[{country:'美国',allowedChannels:['9910::燕文::TEST']}]}],'customer-grades':[{grade:'S',coefficient:1.2,enabled:true}],'exchange-rate':{usdCny:6,eurUsd:1.1,updatedAt:'test'},'tax-settings':fees(.3),'surcharge-settings':fees(.2),'customer-operation-fees':{customers:[]}}
const finance=Object.fromEntries(Object.entries(values).map(([key,value])=>[key,{value,_version:1}]))
const versions=Object.fromEntries(Object.keys(values).map(k=>[k,1]))
const errors=[],saved=[],requests=[]
const browser=await chromium.launch({channel:'chrome',headless:true})
try {
 const context=await browser.newContext({viewport:{width:1600,height:1000},locale:'zh-CN'})
 const page=await context.newPage()
 page.on('pageerror',e=>errors.push(e.message))
 await page.route('**/api/v1/**',async route=>{
  const req=route.request(),u=new URL(req.url()),path=u.pathname.replace('/api/v1',''); requests.push(req.method()+' '+path)
  let data
  if(path==='/auth/me')data={id:'qa-user',account:'LOCALQA',name:'本地验收',role:'super_admin',permissions:['quote','purchase','logistics','finance','allRecords','permissions'],mustChangePassword:false}
  else if(path==='/auth/csrf')data={headerName:'X-CSRF-TOKEN',token:'local-only'}
  else if(path==='/finance-settings')data=finance
  else if(path==='/quotation-readiness')data={ready:false,purchase:{ready:false},logistics:{ready:true},finance:{ready:true},missing:['无采购商品']}
  else if(path==='/quotation-drafts/mine/state')data={exists:false}
  else if(path==='/quotation-templates'||path==='/personal-quotation-customers')data=[]
  else if(path==='/logistics/published/manifest')data={revision:'test',publishedChannels:1,countries:[{code:'US',name:'美国'}],attributes:['普货']}
  else if(path==='/logistics/published/rules'||path==='/logistics/published/catalog')data={revision:'test',rules:[rule]}
  else if(path==='/quotation-sync')data={purchaseVersions:{},logisticsRevision:'test',financeVersions:versions}
  else if(path==='/quotation-sync/logistics')data={revision:'test'}
  else if(path==='/quotations'&&req.method()==='POST'){data={...req.postDataJSON(),id:'saved-'+saved.length,no:'LOCAL-QA',createdAt:new Date().toISOString()};saved.push(data)}
  else if(path.includes('notification'))data={items:[],unreadCount:0,total:0}
  else {errors.push('Unexpected endpoint '+path);data=[]}
  await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:'OK',data})})
 })
 await page.goto(base+'/quotation')
 await page.locator('.mode-field select').waitFor()
 await page.locator('.customer-field input').fill('示例客户')
 for(const mode of ['freight-trial','shipping-only']){
  await page.locator('.mode-field select').selectOption(mode)
  if(mode==='freight-trial')await page.getByLabel('成本价格',{exact:true}).fill('30')
  await page.getByLabel('手填重量',{exact:true}).fill('500')
  await page.getByRole('button',{name:mode==='freight-trial'?'查询试算':'查询代发报价',exact:true}).click()
  await page.getByRole('button',{name:/快速报价/}).click()
  await page.locator('.selection-actions button').first().waitFor()
  await page.locator('.selection-actions button').first().click()
  await page.locator('.sheet-price input').first().waitFor()
  assert.equal(await page.locator('.sheet-price input').first().inputValue(),mode==='freight-trial'?'12.50':'6.50')
  await page.screenshot({path:out+'/'+mode+'.png',fullPage:true})
  await page.evaluate(()=>window.scrollTo(0,0))
  await page.screenshot({path:out+'/'+mode+'-entry.png'})
  const add=page.getByRole('button',{name:'＋ 新增列',exact:true})
  if(await add.count()){
   await add.click();await page.locator('.sheet-quantity input').last().fill('7')
   assert.equal(await page.locator('.sheet-price input').last().inputValue(),mode==='freight-trial'?'78.50':'36.50')
  }
  await page.getByRole('button',{name:/保存 1 张报价单/}).click()
  await page.waitForFunction(()=>document.body.innerText.includes('报价已保存'))
  assert.equal(saved.at(-1).manualPricing.weightGrams,500)
  await writeFile(out+'/'+mode+'-payload.json',JSON.stringify(saved.at(-1),null,2))
 }
 assert.equal(saved.length,2)
 assert.deepEqual(errors,[])
}finally{await browser.close();await writeFile(out+'/browser-report.json',JSON.stringify({errors,requests,saved:saved.length},null,2))}
console.log('Both modes passed real Chromium UI calculation and save checks; screenshots: '+out)
