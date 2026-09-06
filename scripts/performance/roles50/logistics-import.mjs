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
await request('/auth/login',{method:'POST',body:{account:'PERFLOG',password:process.env.PERF_PASSWORD||'PerfAdmin123!'}})
const run=Date.now().toString(36).toUpperCase()
const headers=['区域名称','国家简码','时效最早天数','时效最晚天数','禁运商品','允许商品标记','三边之和','三边最大长度','计泡系数','最小长度','最大长度','最小宽度','最大宽度','最小侧面积','最大侧面积','起始重量','截止重量','起重','运费单价','最小计重','首重','首重价','续重','续重单价','区间运费','挂号费','附加费','燃油附加费率','特殊品含量','是否计抛','是否禁止普货','电话是否必需','分区名称','分区邮编前缀','分区邮编','分区城市','分区省州','排除']
const row=Array(headers.length).fill('');row[0]='美国';row[1]='US';row[2]='5';row[3]='10';row[15]='0';row[16]='2';row[18]='10';row[25]='2'
const rows=[headers,row]
function columnName(c){let result='';for(c++;c;c=Math.floor((c-1)/26))result=String.fromCharCode(65+(c-1)%26)+result;return result}
const zip=new JSZip()
zip.file('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>')
zip.file('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
zip.file('xl/workbook.xml','<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="物流" sheetId="1" r:id="rId1"/></sheets></workbook>')
zip.file('xl/_rels/workbook.xml.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
zip.file('xl/worksheets/sheet1.xml',`<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${rows.map((row,i)=>`<row r="${i+1}">${row.map((v,c)=>`<c r="${columnName(c)}${i+1}" t="inlineStr"><is><t>${v}</t></is></c>`).join('')}</row>`).join('')}</sheetData></worksheet>`)
const buffer=await zip.generateAsync({type:'nodebuffer',compression:'DEFLATE'})
const provider=await request('/logistics/providers',{method:'POST',body:{name:'隔离导入'+run,code:'LIMP-'+run,enabled:false}})
const channel=await request('/logistics/channels',{method:'POST',body:{providerId:provider.id,name:'隔离导入'+run,code:'LIMP-C-'+run,type:'专线',logisticsAttribute:'普货',enabled:false}})
const form=new FormData();form.append('file',new Blob([buffer]),'isolated-logistics-'+run+'.xlsx')
const started=performance.now()
const version=await request('/logistics/channels/'+channel.id+'/imports',{method:'POST',body:form})
const importMs=performance.now()-started
const review=await request('/logistics/rebuild/versions/'+version.id)
const publishStarted=performance.now()
await request('/logistics/rebuild/channels/'+channel.id+'/versions/'+version.id+'/review',{method:'POST',body:{reviewConfirmed:true,removalConfirmed:true,note:'隔离表格导入审核测试，渠道及物流商保持停用'}})
const reviewMs=performance.now()-publishStarted
const readback=await request('/logistics/rebuild/versions/'+version.id)
const report={base,channelId:channel.id,versionId:version.id,importMs,reviewMs,errors:version.errors,validRows:version.validRows,pricingReady:review.pricingReady,status:readback.status,passed:version.errors===0&&version.validRows===1&&readback.status==='published',scope:'One additional logistics session during 50-user soak; disabled provider and channel; standard XLSX one row, no active prices changed'}
await writeFile(process.env.LOGISTICS_IMPORT_OUTPUT||'artifacts/performance/accepted-logistics-import.json',JSON.stringify(report,null,2))
console.log(JSON.stringify(report));if(!report.passed)process.exitCode=1
