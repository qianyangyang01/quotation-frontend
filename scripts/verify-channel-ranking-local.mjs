import {chromium} from 'file:///C:/Users/25490/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs'
import fs from 'node:fs/promises'
import assert from 'node:assert/strict'
import {spawnSync} from 'node:child_process'
const out='outputs/channel-ranking';await fs.mkdir(out,{recursive:true})
const query='startDate=2026-09-01&endDate=2026-09-23'
const timings=[];let result
for(let i=0;i<4;i++){const start=performance.now();const response=await fetch('http://127.0.0.1:18119/api/v1/quotations/channel-ranking?'+query);assert.equal(response.status,200);const body=await response.text();result=JSON.parse(body).data;timings.push({ms:Math.round(performance.now()-start),bytes:Buffer.byteLength(body)})}
assert.equal(result.items.length,5);assert.equal(result.totalUses,890);assert.equal(result.totalGroups,179)
const browser=await chromium.launch({channel:'chrome',headless:true})
const page=await browser.newPage({viewport:{width:1400,height:1100},deviceScaleFactor:1.5,locale:'zh-CN'})
const errors=[],writes=[],external=[],requests=[]
page.on('pageerror',e=>errors.push(e.message))
await page.route('**/*',route=>{const req=route.request(),u=new URL(req.url());if(!['localhost','127.0.0.1'].includes(u.hostname)){external.push(u.origin);return route.abort()}if(!['GET','HEAD'].includes(req.method()))writes.push(req.method()+' '+u.pathname);if(u.pathname.includes('channel-ranking'))requests.push(u.search);return route.continue()})
try{
 await page.goto('http://127.0.0.1:5198/quotation/overview')
 await page.locator('.channel-ranking tbody tr').first().waitFor({timeout:30000})
 await page.locator('.kpis').waitFor({timeout:30000})
 assert.equal(await page.locator('.channel-ranking tbody tr').count(),5)
 await page.screenshot({path:out+'/real-snapshot-overview.png'})
 await page.locator('.channel-ranking').screenshot({path:out+'/real-snapshot-ranking.png'})
 await page.getByRole('button',{name:'查看全部 179 条 →'}).click()
 await page.waitForFunction(()=>document.querySelectorAll('.channel-ranking tbody tr').length===20)
 await page.getByRole('button',{name:'下一页',exact:true}).first().click()
 await page.waitForFunction(()=>document.querySelector('.channel-ranking tbody td i')?.textContent==='21')
 await page.locator('.ranking-pagination select').selectOption('50')
 await page.waitForFunction(()=>document.querySelectorAll('.channel-ranking tbody tr').length===50)
 const downloadPromise=page.waitForEvent('download');await page.getByRole('button',{name:'导出本页',exact:true}).click();await(await downloadPromise).saveAs(out+'/真实副本排行榜-本页.csv')
 await page.locator('.global-filters select').first().selectOption('美国')
 await page.waitForFunction(()=>{const rows=[...document.querySelectorAll('.channel-ranking tbody tr')];return rows.length>0&&rows.every(r=>r.children[1]?.textContent==='美国')})
 // No automatic ranking polling: fixed counts between stable observations.
 const before=requests.length;await page.waitForTimeout(1100);assert.equal(requests.length,before)
 assert.deepEqual(errors,[]);assert.deepEqual(writes,[]);assert.deepEqual(external,[])
 const hash=spawnSync('docker',['exec','quotation-channel-ranking-db-20261119','psql','-U','postgres','-d','ranking','-Atc',"SELECT count(*),md5(string_agg(id::text||payload::text||version::text||updated_at::text, chr(10) ORDER BY id)) FROM quotation_record"],{encoding:'utf8'}).stdout.trim()
 assert.equal(hash,'228|928679e5f4439c4d517cf723993eba17')
 const report={ok:true,source:'本机真实历史备份 2026-09-23（非当前线上数据）',records:228,totalUses:result.totalUses,totalGroups:result.totalGroups,timings,historyHashBefore:'228|928679e5f4439c4d517cf723993eba17',historyHashAfter:hash,errors,writes,external,rankingRequests:requests.length,checks:['默认Top5','展开与20/50条分页','下一页','国家筛选','CSV导出','报价payload/version/updated_at摘要不变']}
 await fs.writeFile(out+'/local-verification.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report))
}finally{await browser.close()}
