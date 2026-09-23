import assert from 'node:assert/strict'
import {mkdir,writeFile,readFile} from 'node:fs/promises'
import {pathToFileURL} from 'node:url'
import {base} from './client.mjs'
const {chromium}=await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE||'C:/Users/25490/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs').href)
const browser=await chromium.launch({channel:'chrome',headless:true})
const mode=process.env.PERF_BROWSER_MODE||'candidate',out='artifacts/performance/browser-'+mode
await mkdir(out,{recursive:true})
const reports=[],errors=[]
const ready={super_admin:'.overview-app .kpis',finance:'.overview-app .kpis',employee:'.jerry-app',logistics:'.logistics-page .workspace-switch'}
async function login(page,account){
  await page.goto(base+'/login');await page.getByPlaceholder('请输入账号',{exact:true}).fill(account);await page.getByPlaceholder('请输入密码',{exact:true}).fill('PerfAdmin123!')
  const started=performance.now();await page.getByRole('button',{name:'登录系统'}).click();return started
}
async function waitReady(page,role){
  if(role==='purchase')await page.getByPlaceholder('搜索 SKU、类别、报价人、尺码、颜色或工厂').waitFor()
  else await page.locator(ready[role]).waitFor({timeout:60000})
  if(role==='super_admin'||role==='finance')await page.waitForFunction(()=>{const b=document.querySelector('.overview-app .export');return b&&!b.disabled},{},{timeout:60000})
}
try{
  for(let trial=0;trial<20;trial++){
    const context=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'}),page=await context.newPage()
    const requests=[];page.on('request',r=>{if(r.url().includes('/api/'))requests.push(r.url().split('/api/v1')[1])});page.on('pageerror',e=>errors.push(e.message))
    const started=await login(page,'PERFADMIN');await waitReady(page,'super_admin')
    reports.push({trial,role:'super_admin',loginToReadyMs:performance.now()-started,requests});console.log(JSON.stringify({trial,loginToReadyMs:reports.at(-1).loginToReadyMs}))
    if(trial===0)await page.screenshot({path:out+'/overview.png',fullPage:true})
    await context.close()
  }
  if(mode==='candidate'){
    for(const [role,account] of [['employee','LOAD001'],['purchase','PERFPUR'],['finance','PERFFIN'],['logistics','PERFLOG'],['super_admin','PERFADMIN']]){
      const context=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'}),page=await context.newPage()
      page.on('pageerror',e=>errors.push(role+': '+e.message))
      const started=await login(page,account);await waitReady(page,role)
      if(role==='logistics'){
        await page.getByRole('button',{name:'运费规则列表',exact:true}).click()
        await page.locator('.modern-filters').waitFor();await page.locator('.scroll[aria-busy="false"]').waitFor()
        assert(await page.locator('.modern-filters').innerText().then(t=>/共 \d+ 条正式价格/.test(t)))
      }
      const paths=await page.locator('.app-topbar nav a').evaluateAll(links=>links.map(a=>a.getAttribute('href')))
      const loginToReadyMs=performance.now()-started
      for(const path of paths){
        await page.locator('.app-topbar nav a[href="'+path+'"]').click();await page.waitForURL(base+path)
        await page.locator('#app > .app-bootstrap').waitFor({state:'hidden'})
        await page.waitForTimeout(700)
        assert(!await page.getByText('无权限',{exact:true}).count())
      }
      await page.screenshot({path:out+'/'+role+'.png',fullPage:true})
      await page.locator('.app-user > button').click();await page.getByRole('button',{name:'退出登录',exact:true}).click();await page.waitForURL(base+'/login')
      await login(page,role==='employee'?'PERFADMIN':'LOAD002');await waitReady(page,role==='employee'?'super_admin':'employee')
      assert((await page.locator('.app-user').innerText()).includes(role==='employee'?'PERFADMIN':'LOAD002'))
      reports.push({role,paths,loginToReadyMs,logoutAndSwitch:true});await context.close()
    }
    const context=await browser.newContext(),page=await context.newPage();page.on('pageerror',e=>errors.push(e.message))
    let reject=true
    await page.route('**/api/v1/purchase-products/analytics-catalog',r=>reject?r.abort('timedout'):r.continue())
    await login(page,'PERFADMIN');await page.getByText('采购类别读取失败，暂不展示统计',{exact:false}).waitFor()
    assert.equal(await page.locator('.overview-app .kpis').count(),0);assert(await page.getByRole('button',{name:'⇩ 导出报表'}).isDisabled())
    reject=false;await page.getByRole('button',{name:'重新读取',exact:true}).click();await waitReady(page,'super_admin')
    const download=page.waitForEvent('download');await page.getByRole('button',{name:'⇩ 导出报表'}).click()
    const file=await download;await file.saveAs(out+'/overview.csv');const csv=await readFile(out+'/overview.csv','utf8');assert(csv.includes('QA-'))
    reports.push({role:'super_admin',failedCatalogBlocksStatistics:true,retryAndExport:true})
    await page.locator('.app-topbar nav a[href="/quotation/records"]').click();await page.waitForURL(base+'/quotation/records')
    await context.setOffline(true)
    await page.locator('.app-topbar nav a[href="/quotation/overview"]').click()
    await page.getByText('采购类别读取失败，暂不展示统计',{exact:false}).waitFor()
    assert.equal(await page.locator('.overview-app .kpis').count(),0);assert(await page.getByRole('button',{name:'⇩ 导出报表'}).isDisabled())
    await context.setOffline(false)
    for(const button of await page.getByRole('button',{name:'重新读取',exact:true}).all())await button.click()
    await waitReady(page,'super_admin');reports.push({role:'super_admin',offlineBlocksFalseStatistics:true,onlineRetryRestoresCompleteData:true})
    await context.close()
  }
}catch(e){errors.push(e.stack)}finally{await browser.close()}
const samples=reports.filter(r=>r.trial!==undefined).map(r=>r.loginToReadyMs).sort((a,b)=>a-b)
const p95=samples[Math.ceil(samples.length*.95)-1]
const report={mode,base,browser:'installed Chrome, headless, 1440x1000, loopback, fresh context per trial',reports,errors,p95,passed:errors.length===0&&samples.length===20&&(mode==='baseline'||p95<=3000)}
await writeFile(out+'/report.json',JSON.stringify(report,null,2));console.log(JSON.stringify({mode,p95,errors,passed:report.passed}))
if(!report.passed)process.exitCode=1
