import assert from 'node:assert/strict'
import {writeFile} from 'node:fs/promises'
import {Session,base} from './client.mjs'
// Run only after the timed workload finishes: LOAD050 is one of its administrators.
const admin=new Session('PERFADMIN'),target=new Session('LOAD050')
await admin.login();await target.login()
const original=(await admin.request('/users')).find(user=>user.account===target.account)
assert.equal(original.role,'super_admin');assert.equal(original.status,'enabled')
const checks=[]
let error
try{
 assert((await target.request('/quotations/analytics-records')).total>0)
 const changed=await admin.request('/users/'+original.id,{method:'PATCH',body:{role:'employee',status:'enabled',version:original.version}})
 const blocked=await target.request('/quotations/analytics-records',{allow:[403]})
 assert.equal(blocked.expectedStatus,403);assert(blocked.data==null)
 assert.equal((await target.request('/auth/me')).role,'employee')
 assert((await target.request('/purchase-products?size=1')).items.length>0)
 checks.push({name:'Existing admin session immediately loses company analytics permission after role change; allowed employee reads continue',passed:true})
 const stale=await admin.request('/users/'+original.id,{method:'PATCH',body:{role:'finance',status:'enabled',version:original.version},allow:[409]})
 assert.equal(stale.expectedStatus,409)
 assert.equal((await admin.request('/users')).find(user=>user.id===original.id).role,'employee')
 checks.push({name:'Stale concurrent account edit cannot overwrite the winning role',passed:true})
 const wrongAccount=await target.request('/purchase-products?size=1',{headers:{'X-Expected-Account':'LOAD001'},allow:[409]})
 assert.equal(wrongAccount.expectedStatus,409);assert.match(wrongAccount.message,/账号.*切换/);assert(wrongAccount.data==null)
 checks.push({name:'An old page expecting another account cannot read through a replaced session cookie',passed:true})
 await admin.request('/users/'+original.id,{method:'PATCH',body:{role:'employee',status:'disabled',version:changed.version}})
 assert.equal((await target.request('/purchase-products?size=1',{allow:[401]})).expectedStatus,401)
 checks.push({name:'Disabled account immediately loses its existing session',passed:true})
}catch(e){error=e.stack}
finally{
 const latest=(await admin.request('/users')).find(user=>user.id===original.id)
 await admin.request('/users/'+original.id,{method:'PATCH',body:{role:original.role,status:original.status,version:latest.version}})
 await target.login();assert.equal(target.user.role,original.role)
 assert((await target.request('/quotations/analytics-records')).total>0)
}
const report={base,checks,error,restored:true,passed:!error&&checks.length===4}
await writeFile('artifacts/performance/session-revocation.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report));if(!report.passed)process.exitCode=1
