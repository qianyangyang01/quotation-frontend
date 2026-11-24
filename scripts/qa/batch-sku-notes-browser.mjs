import assert from 'node:assert/strict'
import {mkdir,writeFile} from 'node:fs/promises'
import {pathToFileURL} from 'node:url'
const {chromium}=await import(pathToFileURL('C:/Users/25490/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs').href)
const base='http://127.0.0.1:5419', out='outputs/batch-sku-notes'
await mkdir(out,{recursive:true})
const browser=await chromium.launch({channel:'chrome',headless:true})
try {
 const page=await browser.newPage({viewport:{width:1600,height:1000},permissions:['clipboard-read','clipboard-write']})
 const errors=[];page.on('pageerror',e=>errors.push(e.message))
 await page.goto(base+'/scripts/qa/batch-sku-notes-fixture.html')
 const fields=page.locator('.sheet-size-rules textarea')
 await fields.first().waitFor()
 assert.equal(await fields.count(),3)
 assert.deepEqual(await fields.evaluateAll(nodes=>nodes.map(n=>n.value)),['Box packaging','Bag packaging','No additional packaging'])
 assert.deepEqual(await page.locator('.sheet-size-rules').evaluateAll(nodes=>nodes.map(n=>n.getAttribute('rowspan'))),[null,null,null])
 await page.locator('.sheet-editor-scroll').screenshot({path:out+'/editor.png'})
 await page.getByRole('button',{name:'预览报价单',exact:true}).click()
 const img=page.locator('.sheet-image-scroll img')
 await img.waitFor()
 const bytes=await img.evaluate(async node=>Array.from(new Uint8Array(await (await fetch(node.src)).arrayBuffer())))
 await writeFile(out+'/quote-image.png',Buffer.from(bytes))
 await page.getByRole('button',{name:'编辑报价单',exact:true}).click()
 await fields.nth(1).fill('Custom note for second SKU')
 await page.getByRole('button',{name:'复制报价数据',exact:true}).click()
 const clipboard=await page.evaluate(async()=>{const items=await navigator.clipboard.read();return {html:await(await items[0].getType('text/html')).text(),text:await(await items[0].getType('text/plain')).text()}})
 assert(!clipboard.html.includes('rowspan='))
 assert(clipboard.text.includes('Custom note for second SKU'))
 const saved=await page.evaluate(()=>window.captureLocal())
 assert.deepEqual(saved.items.map(item=>item.input.customerQuote.sizeRules),['Box packaging','Custom note for second SKU','No additional packaging'])
 assert.deepEqual(saved.sheet.rows.map(row=>row.note),saved.items.map(item=>item.input.customerQuote.sizeRules))
 // Copy the compact table through the real UI and inspect its actual clipboard HTML.
 await page.getByRole('button',{name:'复制图文表格',exact:true}).click()
 const compact=await page.evaluate(async()=>{const [item]=await navigator.clipboard.read();return await(await item.getType('text/html')).text()})
 assert(compact.includes('Custom note for second SKU'));assert(compact.includes('No additional packaging'))
 // Exercise renderer pagination with real canvas, including a long multiline note.
 const rendered=await page.evaluate(async saved=>{
  const {renderCustomerQuoteSheet}=await import('/src/services/customerQuoteSheetRenderer.ts')
  const copy={...saved,rows:Array.from({length:25},(_,i)=>({...saved.rows[i%3],key:String(i),number:i+1,note:i===24?'Last page note\nSecond line':saved.rows[i%3].note}))}
  const drawn=[],original=CanvasRenderingContext2D.prototype.fillText
  CanvasRenderingContext2D.prototype.fillText=function(text,...args){drawn.push(String(text));return original.call(this,text,...args)}
  try {const pages=await renderCustomerQuoteSheet(copy);return {pages:pages.map(p=>({first:p.firstRow,last:p.lastRow})),drawn}}
  finally {CanvasRenderingContext2D.prototype.fillText=original}
 },saved.sheet)
 assert.equal(rendered.pages.length,2)
 assert(rendered.drawn.includes('Last page note'));assert(rendered.drawn.includes('Second line'))
 assert(rendered.drawn.join('').replace(/\s/g,'').includes('CustomnoteforsecondSKU')); // wrapping depends on browser font metrics
 assert.deepEqual(errors,[])
 await writeFile(out+'/browser-result.json',JSON.stringify({status:'passed',notes:saved.items.map(item=>item.input.customerQuote.sizeRules),rows:saved.sheet.rows.map(row=>({sku:row.sku,note:row.note,prices:row.prices})),imagePages:rendered.pages,errors},null,2))
 console.log('PASS: real editor, independent notes, clipboard, save capture and multi-page canvas rendering')
}finally{await browser.close()}
