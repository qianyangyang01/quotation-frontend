import JSZip from 'jszip'
import assert from 'node:assert/strict'
import {base} from './client.mjs'
export async function importLogistics(session){
const request=(path,args={})=>session.request(path,{...args,headers:{'Idempotency-Key':crypto.randomUUID()},label:'logistics-import-'+(args.method||'GET')})
const run=Date.now().toString(36).toUpperCase()+session.account
if(!session.importFixture){
 const providerName='隔离导入'+run,channelName='隔离渠道'+run
 const directory=await request('/logistics/company-channels')
 directory.entries.push({id:crypto.randomUUID(),providerName,channelName,logisticsAttribute:'普货',aliases:[],providerAliases:[],productCodes:['QA-'+run],enabled:true})
 await request('/logistics/company-channels',{method:'PUT',body:directory})
 const provider=await request('/logistics/providers',{method:'POST',body:{name:providerName,code:'LIMP-'+run,enabled:false}})
 session.importFixture={providerName,channelName,providerId:provider.id,sequence:0}
}
const fixture=session.importFixture;fixture.sequence++
const headers=['区域名称','国家简码','时效最早天数','时效最晚天数','禁运商品','允许商品标记','三边之和','三边最大长度','计泡系数','最小长度','最大长度','最小宽度','最大宽度','最小侧面积','最大侧面积','起始重量','截止重量','起重','运费单价','最小计重','首重','首重价','续重','续重单价','区间运费','挂号费','附加费','燃油附加费率','特殊品含量','是否计抛','是否禁止普货','电话是否必需','分区名称','分区邮编前缀','分区邮编','分区城市','分区省州','排除']
const row=Array(headers.length).fill('');row[0]='美国';row[1]='US';row[2]='5';row[3]='10';row[15]='0';row[16]='2';row[18]='10';row[25]='2'
row[18]=String(10+fixture.sequence%3);const rows=[['渠道名称：'+fixture.channelName],headers,row]
function columnName(c){let result='';for(c++;c;c=Math.floor((c-1)/26))result=String.fromCharCode(65+(c-1)%26)+result;return result}
const zip=new JSZip()
zip.file('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>')
zip.file('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
zip.file('xl/workbook.xml','<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="物流" sheetId="1" r:id="rId1"/></sheets></workbook>')
zip.file('xl/_rels/workbook.xml.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
zip.file('xl/worksheets/sheet1.xml',`<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${rows.map((row,i)=>`<row r="${i+1}">${row.map((v,c)=>`<c r="${columnName(c)}${i+1}" t="inlineStr"><is><t>${v}</t></is></c>`).join('')}</row>`).join('')}</sheetData></worksheet>`)
const buffer=await zip.generateAsync({type:'nodebuffer',compression:'DEFLATE'})
const form=new FormData();form.append(fixture.channelId?'file':'files',new Blob([buffer]),fixture.providerName+'-'+run+'.xlsx')
const started=performance.now()
const imported=await request(fixture.channelId?'/logistics/channels/'+fixture.channelId+'/imports':'/logistics/providers/'+fixture.providerId+'/imports',{method:'POST',body:form})
assert(imported.id||imported.items?.[0]?.versionId,'Import did not create a reviewable version: '+JSON.stringify(imported))
let version=imported
if(!fixture.channelId){
 assert.equal(imported.items?.length,1,JSON.stringify(imported));const item=imported.items[0];fixture.channelId=item.channelId
 version=await request('/logistics/rebuild/versions/'+item.versionId)
}
const channel={id:fixture.channelId}
const importMs=performance.now()-started
const review=await request('/logistics/rebuild/versions/'+version.id)
const publishStarted=performance.now()
await request('/logistics/rebuild/channels/'+channel.id+'/versions/'+version.id+'/review',{method:'POST',body:{reviewConfirmed:true,removalConfirmed:true,note:'隔离表格导入审核测试，渠道及物流商保持停用'}})
const reviewMs=performance.now()-publishStarted
const readback=await request('/logistics/rebuild/versions/'+version.id)
const report={base,channelId:channel.id,versionId:version.id,importMs,reviewMs,errors:version.errors,validRows:version.validRows??version.rowCount??version.rows?.length,pricingReady:review.pricingReady,status:readback.status,passed:!(version.errors>0)&&readback.status==='published',scope:'One of the configured logistics workers imports and publishes an isolated registered channel; provider disabled, unrelated active prices preserved'}

assert(report.passed,JSON.stringify(report));return report
}
