import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { chromium } from 'file:///C:/Users/25490/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs'
const fixture=JSON.parse(await readFile('outputs/quantity-sort-preview/fixture.json','utf8'))
const browser=await chromium.launch({channel:'msedge',headless:true})
const results=[]
try{
 for(const count of (process.env.PERF_COUNTS || '100,1000').split(',').map(Number)){
  for(const variant of (process.env.PERF_VARIANTS || 'baseline,candidate').split(',')){
   const base=variant==='baseline'?'http://127.0.0.1:5201':process.env.PERF_CANDIDATE_URL || 'http://127.0.0.1:5200'
   const data=structuredClone(fixture)
   data.rules=Array.from({length:count},(_,i)=>{const rule=structuredClone(fixture.rules[i%fixture.rules.length]);rule.id=20000+i;rule.name+=' PERF-'+i;rule.logisticsChannelId='perf-'+i;rule.relations[0].channel=rule.name;rule.relations[0].channelCode='PERF'+i;return rule})
   data.finance['channel-policies'].value[0].countryRules[0].allowedChannels=data.rules.map(rule=>`${rule.id}::${rule.relations[0].carrier}::${rule.relations[0].channelCode}`)
   const context=await browser.newContext({viewport:{width:1600,height:1000},locale:'zh-CN'})
   const page=await context.newPage(),errors=[],requests=[]
   page.on('pageerror',e=>errors.push(e.message))
   await page.route('**/api/v1/**',async route=>{
    const req=route.request(),path=new URL(req.url()).pathname.replace('/api/v1','');requests.push(req.method()+' '+path)
    if(req.method()!=='GET')throw new Error('Performance test must be read-only: '+path)
    let value=[]
    if(path==='/auth/me')value={id:'perf-user',account:'PERF',name:'本地性能测试',role:'super_admin',permissions:['quote','purchase','logistics','finance','allRecords','permissions'],mustChangePassword:false}
    else if(path==='/auth/csrf')value={headerName:'X-CSRF-TOKEN',token:'local-only'}
    else if(path==='/finance-settings')value=data.finance
    else if(path==='/quotation-readiness')value={ready:false,purchase:{ready:false},logistics:{ready:true},finance:{ready:true},missing:['无采购商品']}
    else if(path==='/quotation-drafts/mine/state')value={exists:false}
    else if(path==='/logistics/published/manifest')value={revision:'local-perf',publishedChannels:count,countries:[{code:'US',name:'美国'}],attributes:['普货']}
    else if(path==='/logistics/published/rules'||path==='/logistics/published/catalog')value={revision:'local-perf',rules:data.rules}
    else if(path==='/quotation-sync')value={purchaseVersions:{},logisticsRevision:'local-perf',financeVersions:data.versions}
    else if(path==='/quotation-sync/logistics')value={revision:'local-perf'}
    else if(path.includes('notification'))value={items:[],unreadCount:0,total:0}
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:'OK',data:value})})
   })
   await page.goto(base+'/quotation');await page.locator('.mode-field select').waitFor()
   await page.locator('.customer-field input').fill('本地压力测试')
   await page.locator('.mode-field select').selectOption('shipping-only');await page.getByLabel('手填重量',{exact:true}).fill('200')
   const queryStart=performance.now()
   await page.getByRole('button',{name:'查询代发报价',exact:true}).click();await page.getByRole('button',{name:/快速报价/}).click()
   await page.locator('.common-matrix .selection-actions button').first().waitFor()
   const queryMs=performance.now()-queryStart
   const metrics={variant,count,queryMs:Number(queryMs.toFixed(1)),samples:[]}
   const requestCount=requests.length
   // Real DOM events in production build; two animation frames include Vue DOM updates.
   const measure=async(action,value)=>page.evaluate(async({action,value})=>{
    const start=performance.now(),root=document.querySelector('.common-matrix')
    const settle=()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))
    if(action==='apply'){
     const field=root.querySelector('[aria-label="比价数量"]');field.value=value;field.dispatchEvent(new Event('input',{bubbles:true}));await Promise.resolve()
     ;[...root.querySelectorAll('button')].find(button=>button.textContent==='应用数量').click()
    }else if(action==='sort'){
     const select=root.querySelector('[aria-label="排序数量"]')
     if(select){select.value=value;select.dispatchEvent(new Event('change',{bubbles:true}))}
     else [...root.querySelectorAll('button')].find(button=>button.textContent.includes('价格从低到高')).click()
    }else if(action==='search'){
     const input=root.querySelector('[aria-label="搜索物流渠道"]');input.value=value;input.dispatchEvent(new Event('input',{bubbles:true}))
    }else if(action==='page') [...root.querySelectorAll('footer button')].find(button=>button.textContent==='下一页')?.click()
    await settle();return Number((performance.now()-start).toFixed(1))
   },{action,value})
   for(let iteration=0;iteration<Number(process.env.PERF_ROUNDS || 4);iteration++){
    if(variant==='candidate')await measure('apply','1,2,3')
    const sample={iteration}
    if(variant==='candidate'){
     let session
     if(process.env.PERF_PROFILE==='true'){session=await context.newCDPSession(page);await session.send('Profiler.enable');await session.send('Profiler.start')}
     sample.applyMs=await measure('apply','4,8,12')
     if(session){const {profile}=await session.send('Profiler.stop');await writeFile('outputs/quantity-sort-preview/comparison.cpuprofile',JSON.stringify(profile));await session.detach()}
    }
    sample.sortMs=await measure('sort','8')
    sample.searchMs=await measure('search','PERF')
    sample.pageMs=await measure('page','')
    await measure('search','')
    metrics.samples.push(sample)
    console.log(JSON.stringify({variant,count,...sample}))
   }
   assert.deepEqual(errors,[])
   metrics.backgroundRequests=requests.slice(requestCount)
   assert.ok(metrics.backgroundRequests.every(request=>/quotation-sync|notification/.test(request)),JSON.stringify(metrics.backgroundRequests))
   metrics.extraRequests=requests.length-requestCount
   results.push(metrics);console.log(JSON.stringify(metrics))
   await context.close()
  }
 }
 await writeFile('outputs/quantity-sort-preview/performance.json',JSON.stringify({environment:'Local Edge headless, production build, synthetic rate fixtures, same machine, serial runs; timings include two animation frames; first sample warm-up',results},null,2))
}finally{await browser.close()}
