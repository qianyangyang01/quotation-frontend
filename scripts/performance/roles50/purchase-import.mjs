import JSZip from 'jszip'
import { writeFile } from 'node:fs/promises'
const base=process.env.PERF_BASE_URL||'http://127.0.0.1:18098'
if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(base))throw new Error('Only isolated loopback allowed')
const cookies=new Map()
let csrf
async function request(path,{method='GET',body}={}){
  const headers={Accept:'application/json',Cookie:[...cookies].map(([k,v])=>`${k}=${v}`).join('; ')}
  if(csrf&&method!=='GET')headers[csrf.headerName]=csrf.token
  if(body&&!(body instanceof FormData)){headers['Content-Type']='application/json';body=JSON.stringify(body)}
  if(method==='POST')headers['Idempotency-Key']=crypto.randomUUID()
  const response=await fetch(base+'/api/v1'+path,{method,headers,body,signal:AbortSignal.timeout(30000)})
  for(const value of response.headers.getSetCookie()){const pair=value.split(';')[0];const i=pair.indexOf('=');cookies.set(pair.slice(0,i),pair.slice(i+1))}
  const result=await response.json()
  if(!response.ok)throw new Error(`${path}: ${response.status} ${result.code}`)
  return result.data
}
csrf=await request('/auth/csrf')
await request('/auth/login',{method:'POST',body:{account:'PERFPUR',password:process.env.PERF_PASSWORD||'PerfAdmin123!'}})
const run=Date.now().toString(36).toUpperCase(),count=1000
const headers=['SKU','类别','报价人','报价日期','克重(g)','起订量(件)','基准采购单价(CNY/件)','1件总运费(CNY)','10件总运费(CNY)','是否有货']
const rows=[headers,...Array.from({length:count},(_,i)=>[`IMP-${run}-${i}`,'服装','隔离采购','2026-09-06','100','1','10','1','3','有货'])]
const zip=new JSZip()
zip.file('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>')
zip.file('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
zip.file('xl/workbook.xml','<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="采购" sheetId="1" r:id="rId1"/></sheets></workbook>')
zip.file('xl/_rels/workbook.xml.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
zip.file('xl/worksheets/sheet1.xml',`<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${rows.map((row,i)=>`<row r="${i+1}">${row.map((v,c)=>`<c r="${String.fromCharCode(65+c)}${i+1}" t="inlineStr"><is><t>${v}</t></is></c>`).join('')}</row>`).join('')}</sheetData></worksheet>`)
const buffer=await zip.generateAsync({type:'nodebuffer',compression:'DEFLATE'})
const form=new FormData();form.append('file',new Blob([buffer]),`isolated-${run}.xlsx`);form.append('importMode','text-only')
const started=Date.now(),uploaded=await request('/purchase-imports/jobs',{method:'POST',body:form})
async function waitFor(states){
  const deadline=Date.now()+120000
  while(Date.now()<deadline){
    const job=await request(`/purchase-imports/jobs/${uploaded.id}`)
    if(states.includes(job.status))return job
    if(['failed','cancelled','completed-with-errors'].includes(job.status))throw new Error(JSON.stringify({status:job.status,error:job.error}))
    await new Promise(resolve=>setTimeout(resolve,1000))
  }
  throw new Error('Import timeout')
}
const ready=await waitFor(['ready'])
if(ready.validRows!==count||ready.errorRows!==0)throw new Error(`Invalid fixture: ${ready.validRows}/${ready.errorRows}`)
await request(`/purchase-imports/jobs/${uploaded.id}/confirm`,{method:'POST',body:{duplicateSelections:{}}})
const completed=await waitFor(['completed'])
const product=await request(`/purchase-products/IMP-${run}-999`)
const report={base,jobId:uploaded.id,rows:count,added:completed.addedRows,errors:completed.errorRows,elapsedMs:Date.now()-started,passed:completed.addedRows===count&&product.purchasePriceCny===10&&product.weightG===100}
await writeFile(process.env.IMPORT_OUTPUT||'artifacts/performance/purchase-import.json',JSON.stringify(report,null,2))
console.log(JSON.stringify(report))
if(!report.passed)process.exitCode=1
