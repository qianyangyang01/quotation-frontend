import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {pathToFileURL} from 'node:url'
import {execFileSync} from 'node:child_process'
const {chromium}=await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE || 'C:/Users/25490/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs').href)
const base=process.env.QA_BASE_URL || 'http://127.0.0.1:5397'
assert.equal(new URL(base).hostname,'127.0.0.1')
const browser=await chromium.launch({channel:'chrome',headless:process.env.QA_WPS!=='1'})
try {
  const page=await browser.newPage({viewport:{width:1500,height:900},permissions:['clipboard-read','clipboard-write']})
  await page.goto(base+'/scripts/qa/compact-quote-fixture.html')
  await page.getByRole('button',{name:'复制图文表格',exact:true}).first().waitFor()
  const report=await page.evaluate(async()=>{
    const {compactQuoteTable,copyCompactQuoteTable}=await import('/src/services/compactQuoteTable.ts')
    const sheet={agent:'Local QA',date:'2026-11-20',quantityLabels:['1 pc','2 pcs','3 pcs'],issues:[],rows:['SKU-A','SKU-A','SKU-B','SKU-B','SKU-A'].map((sku,i)=>({key:String(i),number:i+1,sku,country:['US','GB','US','GB','CA'][i],provider:'Synthetic carrier',shippingTime:'',prices:[10+i,20+i,30+i]}))}
    const before=JSON.stringify(sheet),table=compactQuoteTable(sheet)
    document.querySelector('#app').innerHTML=table.table
    await copyCompactQuoteTable(table,()=>{})
    const doc=new DOMParser().parseFromString(table.html,'text/html')
    return {unchanged:before===JSON.stringify(sheet),html:table.html,
      headers:[...doc.querySelectorAll('[data-copy-metadata="header"]')].map(cell=>cell.textContent),
      skuGroups:[...doc.querySelectorAll('tbody tr')].flatMap(row=>[...row.cells].filter(cell=>cell.textContent.startsWith('SKU-')).map(cell=>({sku:cell.textContent,span:cell.rowSpan}))),
      prices:[...doc.querySelectorAll('tbody tr')].map(row=>[...row.querySelectorAll('td[x\\:num]')].map(cell=>Number(cell.getAttribute('x:num'))))}
  })
  assert.equal(report.unchanged,true)
  assert.deepEqual(report.headers,['报价人','报价日期','报价客户','客户等级','客户佣金'])
  assert.deepEqual(report.skuGroups,[{sku:'SKU-A',span:2},{sku:'SKU-B',span:2},{sku:'SKU-A',span:1}])
  assert.deepEqual(report.prices,[[10,20,30],[11,21,31],[12,22,32],[13,23,33],[14,24,34]])
  await page.screenshot({path:'outputs/compact-regression/multi-sku-table.png',fullPage:true})
  await writeFile('outputs/compact-regression/multi-sku-browser-result.json',JSON.stringify(report,null,2))
  console.log('PASS: multi-SKU HTML clipboard preserves nonadjacent groups and all numeric prices.')
  if(process.env.QA_WPS==='1') {
  execFileSync('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File','scripts/qa/compact-quote-wps.ps1','-MultiSku'],{encoding:'utf8',windowsHide:true})
  const result=JSON.parse((await readFile('outputs/compact-quote/wps-multi-sku-result.json','utf8')).replace(/^\uFEFF/,''))
  assert.deepEqual(result.dataRows.map(row=>row.sku),['SKU-A','SKU-A','SKU-B','SKU-B','SKU-A'])
  assert.deepEqual(result.dataRows.map(row=>row.prices),[[10,20,30],[11,21,31],[12,22,32],[13,23,33],[14,24,34]])
  assert.deepEqual(result.metadataHeaders,['报价人','报价日期','报价客户','客户等级','客户佣金'])
  assert.deepEqual(result.dataRows.map(row=>row.skuMerged),['$F$3:$F$4','$F$3:$F$4','$F$5:$F$6','$F$5:$F$6','$F$7'])
  assert.deepEqual(result.dataRows.map(row=>row.photoMerged),['$G$3:$G$4','$G$3:$G$4','$G$5:$G$6','$G$5:$G$6','$G$7'])
  await writeFile('outputs/compact-regression/multi-sku-result.json',JSON.stringify({result:'passed',...result},null,2))
  console.log('PASS: WPS multi-SKU keeps A,A,B,B,A order, adjacent SKU/product cells merge separately, all 15 prices retain row alignment.')
  }
} finally {await browser.close()}
