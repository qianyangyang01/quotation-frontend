import { createRequire } from 'node:module'
import assert from 'node:assert/strict'
// Optional external tooling path avoids adding Playwright to the production app.
const require = createRequire(process.env.QUOTE_QA_MODULE_ROOT || import.meta.url)
const { chromium } = require('playwright')
const origin = process.env.QUOTE_QA_ORIGIN || 'http://127.0.0.1:5181'
assert(['127.0.0.1', 'localhost', '[::1]'].includes(new URL(origin).hostname), 'QA fixtures must run on loopback, never a production browser session')
const compiled = await (await fetch(origin + '/src/components/quotation/CustomerQuoteSheet.vue')).text()
const vueUrl = compiled.match(/from "([^"\n]*vue\.js[^"\n]*)"/)[1]
const html = `<!doctype html><html><head><meta charset="utf-8"></head><body><main id="app"></main><div id="paste" contenteditable="true" aria-label="Paste target"></div><script type="module">
import {createApp,h,reactive,nextTick} from '${vueUrl}';
import Sheet from '/src/components/quotation/CustomerQuoteSheet.vue';
import Actions from '/src/components/quotation/QuotationRecordCopyActions.vue';
import {buildCustomerQuoteSheet,newQuoteSheetEdits} from '/src/data/customerQuoteSheet.ts';
import {renderCustomerQuoteSheet} from '/src/services/customerQuoteSheetRenderer.ts';
import {normalizeQuotationRecord} from '/src/data/quotationRecords.ts';
const providers=['万邦','云途','云速递','容鼎','捷易通达','极通环球','燕文','百洲','花海','递四方','通邮','闪电猴','顺丰','顺友'];
const row=(i)=>({country:'美国',quoteRegion:'全国统一',carrier:providers[i%14],channelKey:'route-'+i,ruleId:1,rule:'PRIVATE-RULE',channelCode:'PRIVATE-'+i,transport:'PRIVATE-CHANNEL',eta:'6～12 天',quote1:12.8,quote2:20.5,quote3:28.2,quoteCustom:43.6});
const state=reactive({rows:Array.from({length:14},(_,i)=>row(i)),countries:[],salesperson:'Alex',contextKey:'product-1',customQuantity:5,bundle:false,sourcePending:false});
let app=createApp({render:()=>h(Sheet,state)});app.mount('#app');
window.fixture={state,nextTick,row,buildCustomerQuoteSheet,newQuoteSheetEdits,renderCustomerQuoteSheet,mountRecord(mode){app.unmount();const record=normalizeQuotationRecord({id:'record-'+mode,no:'QA',matrixMode:mode,salespersonName:'Alex',customQuoteQuantity:7,quoteMode:'bundle',quoteOptions:[{id:'one',country:'美国',countryCode:'US',carrier:'闪电猴',channel:'PRIVATE',rule:'PRIVATE',eta:'5～12 天',quote1Usd:6.2,quote2Usd:null,quote3Usd:0,quoteCustomUsd:15.9}]});app=createApp({render:()=>h(Actions,{record})});app.mount('#app');},unmount(){app.unmount()}};
</script></body></html>`
const browser = await chromium.launch({ headless:true, executablePath:process.env.QUOTE_QA_BROWSER || undefined })
const context = await browser.newContext({ permissions:['clipboard-read','clipboard-write'], viewport:{width:1440,height:1000} })
const results = [], errors = [], requests = []
let failNotes = true
await context.route('**/*', async route => {
  const req=route.request(); requests.push({url:req.url(),method:req.method()})
  if (req.url().includes('/api/') || req.method() !== 'GET') return route.abort()
  if(req.url().endsWith('/__quote-qa')) return route.fulfill({contentType:'text/html',body:html})
  if(failNotes && req.resourceType() === 'image' && req.url().includes('/assets/quote-sheet/notes.png')) return route.abort()
  return route.continue()
})
await context.addInitScript(()=>{
  window.storageWrites=[];
  Storage.prototype.setItem=function(){window.storageWrites.push('Storage.setItem');throw Error('Unexpected storage write')};
  IDBFactory.prototype.open=function(){window.storageWrites.push('IndexedDB.open');throw Error('Unexpected IndexedDB write')};
})
const page = await context.newPage()
page.on('pageerror',e=>errors.push(e.message))
try {
  await page.goto(origin+'/__quote-qa'); await page.waitForFunction(()=>window.fixture)
  await page.getByRole('button',{name:'预览报价单',exact:true}).click()
  await page.getByRole('alert').filter({hasText:'素材加载失败'}).waitFor()
  const alertBounds = await page.getByRole('alert').boundingBox(); assert(alertBounds.y >= 0 && alertBounds.y < 1000)
  results.push('asset failure produces a visible retryable error')
  failNotes=false
  await page.getByRole('button',{name:'预览报价单',exact:true}).click()
  await page.locator('.sheet-image-scroll img').waitFor()
  assert.equal(await page.locator('.sheet-accessible tbody tr').count(),14)
  await page.getByRole('button',{name:'复制报价数据',exact:true}).click()
  await page.getByRole('status').filter({hasText:'已复制 14 条'}).waitFor()
  const text=await page.evaluate(()=>navigator.clipboard.readText())
  assert.equal(text.split('\r\n').length,15); assert(text.includes('Hua Hai'));assert(text.includes('SDH Express'));assert(!text.includes('PRIVATE'))
  await page.locator('#paste').focus(); await page.keyboard.press('Control+V')
  assert((await page.locator('#paste').innerText()).includes('SDH Express'))
  results.push('14 providers / 14 routes / 9 columns: real text clipboard and browser paste passed')
  await page.getByRole('button',{name:'复制报价图片',exact:true}).click()
  await page.getByRole('status').filter({hasText:'报价图片已复制'}).waitFor()
  const imageCheck=await page.evaluate(async()=>{
    const item=(await navigator.clipboard.read()).find(item=>item.types.includes('image/png'));
    const copied=await item.getType('image/png');const preview=await(await fetch(document.querySelector('.sheet-image-scroll img').src)).blob();
    async function pixels(blob){const bitmap=await createImageBitmap(blob);const canvas=document.createElement('canvas');canvas.width=bitmap.width;canvas.height=bitmap.height;const ctx=canvas.getContext('2d');ctx.drawImage(bitmap,0,0);const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',ctx.getImageData(0,0,canvas.width,canvas.height).data))).join(',');return{width:bitmap.width,height:bitmap.height,hash}}
    return{copied:await pixels(copied),preview:await pixels(preview)};
  })
  assert.deepEqual(imageCheck.copied,imageCheck.preview);results.push('actual PNG clipboard decoded pixels equal the on-screen preview')
  await page.getByRole('button',{name:'编辑报价单',exact:true}).click()
  await page.getByLabel('第 1 行运输时效',{exact:true}).fill('18-25 days')
  await page.evaluate(async()=>{window.fixture.state.rows.reverse();await window.fixture.nextTick()})
  await page.getByRole('button',{name:'复制报价数据',exact:true}).click();await page.getByRole('status').filter({hasText:'已复制 14 条'}).waitFor()
  assert((await page.evaluate(()=>navigator.clipboard.readText())).split('\r\n').at(-1).includes('18-25 days'))
  await page.evaluate(async()=>{window.fixture.state.contextKey='product-2';await window.fixture.nextTick()})
  await page.getByRole('button',{name:'复制报价数据',exact:true}).click();await page.getByRole('status').filter({hasText:'已复制 14 条'}).waitFor()
  assert(!(await page.evaluate(()=>navigator.clipboard.readText())).includes('18-25 days'))
  results.push('real component: route reorder retains manual ETA; product change removes it')
  const pages=await page.evaluate(async()=>{
    const f=window.fixture; const results=[];
    for(const count of [6,24,25,49]){
      const sheet=f.buildCustomerQuoteSheet({rows:Array.from({length:count},(_,i)=>f.row(i)),countries:[],edits:f.newQuoteSheetEdits('Alex'),customQuantity:7,bundle:true});
      const images=await f.renderCustomerQuoteSheet(sheet); results.push({count,pages:images.map(x=>({width:x.width,height:x.height,first:x.firstRow,last:x.lastRow,size:x.blob.size}))});
    }return results;
  })
  assert.deepEqual(pages.map(x=>x.pages.length),[1,1,2,3]);assert.equal(pages[0].pages[0].height,1024)
  assert.deepEqual(pages[3].pages.map(p=>[p.first,p.last]),[[1,24],[25,48],[49,49]])
  assert(pages.every(p=>p.pages.every(x=>x.width===1536&&x.size>10000)))
  results.push({pagination:pages})
  for(const mode of ['common','specified','template']){
    await page.evaluate(mode=>window.fixture.mountRecord(mode),mode)
    await page.locator('.record-copy-buttons').getByRole('button',{name:'复制报价数据',exact:true}).click()
    await page.locator('.record-copy-actions>p').filter({hasText:'已复制'}).waitFor()
    const tsv=await page.evaluate(()=>navigator.clipboard.readText())
    assert(tsv.includes('7 sets (USD)'));assert(tsv.includes('$6.20\t—\t$0.00\t$15.90'));assert(!tsv.includes('PRIVATE'))
    await page.locator('.record-copy-buttons').getByRole('button',{name:'复制报价图片',exact:true}).click()
    await page.locator('dialog[open] .sheet-image-scroll img').waitFor()
    await page.getByRole('button',{name:'关闭客户报价单',exact:true}).click()
    assert.equal(await page.locator('dialog[open]').count(),0)
  }
  results.push('all 3 saved quotation modes: footer data copy, image preview, close, null/zero prices and bundle quantities passed')
  assert.deepEqual(await page.evaluate(()=>window.storageWrites),[])
  assert.deepEqual(requests.filter(r=>r.method!=='GET'||r.url.includes('/api/')),[])
  assert.deepEqual(errors,[])
  results.push('no business API requests, storage writes, IndexedDB access or uncaught page errors')
  console.log(JSON.stringify({ok:true,results},null,2))
} catch(error) {console.error(JSON.stringify({errors,requests:requests.slice(-8)},null,2));throw error}
finally {await context.close();await browser.close()}
