// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { compactQuoteTable, copyCompactQuoteTable } from './compactQuoteTable'
import type { CustomerQuoteSheet } from '@/data/customerQuoteSheet'
const sheet=():CustomerQuoteSheet=>({agent:'Private agent',date:'2026-11-20',quantityLabels:['1 pc','2 pcs','3 pcs'],sizeRules:'S / M / L',issues:[],rows:[
  {key:'a',number:1,sku:'TC2602578-5\n30CM',country:'United States',provider:'50%顺丰商派 + 50%燕文',shippingTime:'7 days',prices:[37.45,72.95,108.50],sourceDescription:'Private cost'},
  {key:'b',number:2,sku:'TC2602578-5\n30CM',country:'United Kingdom',provider:'SF Express',shippingTime:'7 days',prices:[29.10,54.70,80.30],sourceDescription:'Private cost'},
]})
afterEach(()=>vi.unstubAllGlobals())
it('follows moved column groups, quantity columns and row order without changing prices',()=>{
  const input=sheet()
  input.columnOrder=['provider','prices','country','sku','product','sizeRules']
  input.quantityLabels=['3 pcs','1 pc','2 pcs']
  input.rows=input.rows.reverse().map(row=>({...row,prices:[row.prices[2],row.prices[0],row.prices[1]]}))
  const before=JSON.stringify(input)
  const result=compactQuoteTable(input)
  const host=document.createElement('div');host.innerHTML=result.table
  expect([...host.querySelectorAll('thead tr:first-child th:not([data-copy-metadata])')].map(cell=>cell.textContent)).toEqual(['Logistics Provider','Quote by Quantity (USD)','Country / Zone','SKU','Product','Size Rules'])
  expect([...host.querySelectorAll('thead tr:last-child th')].map(cell=>cell.textContent)).toEqual(['3 pcs','1 pc','2 pcs'])
  expect([...host.querySelectorAll('tbody tr:first-child td')].slice(5,10).map(cell=>cell.textContent)).toEqual(['SF Express','80.30','29.10','54.70','United Kingdom'])
  expect(result.text.split('\n')[1].split('\t').slice(5,10)).toEqual(['SF Express','80.30','29.10','54.70','United Kingdom'])
  expect(JSON.stringify(input)).toBe(before)
})
it('merges repeated SKU and photos while keeping exact prices as numeric cells',()=>{
  const input=sheet(),before=JSON.stringify(input)
  const result=compactQuoteTable(input,[{dataUrl:'data:image/png;base64,AAAA',width:120,height:90}])
  const host=document.createElement('div');host.innerHTML=result.table
  const bodyRows=host.querySelectorAll('tbody tr')
  expect(bodyRows).toHaveLength(2)
  expect(bodyRows[0].querySelectorAll('[rowspan="2"]')).toHaveLength(3)
  expect(bodyRows[1].children).toHaveLength(10)
  expect(host.querySelector('img')!.getAttribute('src')).toBe('data:image/png;base64,AAAA')
  expect([...host.querySelectorAll('td[x\\:num]')].map(cell=>cell.getAttribute('x:num'))).toEqual(['37.45','72.95','108.50','29.10','54.70','80.30'])
  expect(result.text.split('\n')[2].split('\t').slice(5).join('\t').startsWith('\t\t\tUnited Kingdom')).toBe(true)
  expect(result.html).toContain('50%顺丰商派 + 50%燕文')
  expect(result.html).not.toContain('Private')
  expect(JSON.stringify(input)).toBe(before)
})
it('merges only adjacent identical SKUs without reordering or merging countries',()=>{
  const input=sheet();input.rows.push({...input.rows[0],key:'c',sku:'NEXT'})
  const host=document.createElement('div');host.innerHTML=compactQuoteTable(input).table
  expect([...host.querySelectorAll('tbody tr')].map(row=>row.children.length)).toEqual([13,10,13])
  expect(host.querySelectorAll('tbody [rowspan="1"]')).toHaveLength(3)
})
it('keeps separated blocks of the same SKU separate in a multi-SKU quotation',()=>{
  const input=sheet()
  input.rows=['SKU-A','SKU-A','SKU-B','SKU-B','SKU-A'].map((sku,index)=>({...input.rows[index%2],key:String(index),sku,prices:[10+index,20+index,30+index]}))
  const before=JSON.stringify(input),result=compactQuoteTable(input)
  const host=document.createElement('div');host.innerHTML=result.table
  const rows=[...host.querySelectorAll('tbody tr')]
  expect(rows.map(row=>row.children.length)).toEqual([13,10,13,10,13])
  expect(rows.filter(row=>row.querySelector('[rowspan]')).map(row=>[row.querySelector('[rowspan]')!.textContent,row.querySelector('[rowspan]')!.getAttribute('rowspan')])).toEqual([['SKU-A','2'],['SKU-B','2'],['SKU-A','1']])
  expect([...host.querySelectorAll('td[x\\:num]')].map(cell=>cell.getAttribute('x:num'))).toEqual(input.rows.flatMap(row=>row.prices.map(price=>price!.toFixed(2))))
  expect(JSON.stringify(input)).toBe(before)
})
it('preserves nulls and zero without inventing prices, and rejects invalid input',()=>{
  const input=sheet();input.rows[0].prices=[null,0,0.01]
  const result=compactQuoteTable(input)
  expect(result.html).toContain('x:num="0.00"');expect(result.html).toContain('x:num="0.01"')
  expect(result.text).toContain('—\t0.00\t0.01')
  input.rows[0].prices[0]=NaN;expect(()=>compactQuoteTable(input)).toThrow('价格')
  input.rows=[];expect(()=>compactQuoteTable(input)).toThrow('渠道')
})
it('escapes user fields and restricts images to embedded PNG data',()=>{
  const input=sheet();input.rows[0].sku='<script>alert(1)</script>';input.sizeRules='=1+1';input.rows[0].provider='" onclick="bad'
  const result=compactQuoteTable(input)
  expect(result.html).not.toContain('<script>');expect(result.html).toContain('&lt;script&gt;')
  expect(result.text).toContain("'=1+1")
  expect(()=>compactQuoteTable(input,[{dataUrl:'https://untrusted.test/x.png',width:1,height:1}])).toThrow('格式')
})
it('does not silently fall back to text when rich clipboard writing is rejected',async()=>{
  vi.stubGlobal('isSecureContext',true);vi.stubGlobal('ClipboardItem',class {})
  const write=vi.fn().mockRejectedValue(new Error('denied')),writeText=vi.fn()
  vi.stubGlobal('navigator',{clipboard:{write,writeText}})
  await expect(copyCompactQuoteTable(compactQuoteTable(sheet()),()=>{})).rejects.toThrow('未复制成功')
  expect(writeText).not.toHaveBeenCalled()
})


it('prepends five orange columns beside the existing headers and blank cells beside every quotation row',()=>{
  const input=sheet(),before=JSON.stringify(input),result=compactQuoteTable(input)
  const host=document.createElement('div');host.innerHTML=result.table
  const headers=[...host.querySelectorAll('th[data-copy-metadata="header"]')]
  expect(headers.map(cell=>cell.textContent)).toEqual(['报价人','报价日期','报价客户','客户等级','客户佣金'])
  expect(headers.every(cell=>cell.getAttribute('style')!.includes('background:#f98019'))).toBe(true)
  expect(host.querySelectorAll('thead tr')).toHaveLength(2)
  expect(headers.every(cell=>cell.parentElement===host.querySelector('thead tr') && cell.getAttribute('rowspan')==='2')).toBe(true)
  for (const row of host.querySelectorAll('tbody tr')) {
    expect([...row.children].slice(0,5).every(cell=>cell.getAttribute('data-copy-metadata')==='blank' && cell.textContent==='')).toBe(true)
  }
  expect(result.text.split('\n')).toHaveLength(3)
  expect(result.text.split('\n')[0].split('\t').slice(0,5)).toEqual(['报价人','报价日期','报价客户','客户等级','客户佣金'])
  expect(result.text.split('\n').slice(1).every(row=>row.split('\t').slice(0,5).every(value=>value===''))).toBe(true)
  expect(JSON.stringify(input)).toBe(before)
  expect(result.html).not.toContain('Private agent')
  expect(host.querySelectorAll('tbody tr')).toHaveLength(2)
})
