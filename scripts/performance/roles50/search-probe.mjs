import { writeFile } from 'node:fs/promises'
const base=process.env.PERF_BASE_URL||'http://127.0.0.1:18098'
if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(base))throw new Error('Only isolated loopback allowed')
const cookies=new Map()
let csrf
async function get(path,body){
  const headers={Accept:'application/json',Cookie:[...cookies].map(([k,v])=>`${k}=${v}`).join('; ')}
  if(body){headers['Content-Type']='application/json';headers[csrf.headerName]=csrf.token}
  const response=await fetch(base+'/api/v1'+path,{method:body?'POST':'GET',headers,body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(30000)})
  for(const value of response.headers.getSetCookie()){const pair=value.split(';')[0];const i=pair.indexOf('=');cookies.set(pair.slice(0,i),pair.slice(i+1))}
  const result=await response.json();if(!response.ok)throw new Error(`${path}: ${response.status}`)
  return result.data
}
csrf=await get('/auth/csrf');await get('/auth/login',{account:'PERFPUR',password:process.env.PERF_PASSWORD||'PerfAdmin123!'})
const queries=['服装','性能','100','PERF-SKU-000','不存在的工厂'],samples=Object.fromEntries(queries.map(q=>[q,[]]))
for(let i=0;i<30;i++)for(const q of queries){
  const start=performance.now();const page=await get(`/purchase-products?q=${encodeURIComponent(q)}&page=0&size=50`)
  samples[q].push({ms:performance.now()-start,total:page.total})
  await new Promise(resolve=>setTimeout(resolve,250))
}
const results=Object.fromEntries(Object.entries(samples).map(([q,values])=>{
  const sorted=values.map(v=>v.ms).sort((a,b)=>a-b)
  return [q,{count:sorted.length,p95Ms:sorted[Math.ceil(sorted.length*.95)-1],maxMs:sorted.at(-1),lastTotal:values.at(-1).total}]
}))
await writeFile(process.env.PROBE_OUTPUT||'artifacts/performance/search-probe.json',JSON.stringify({base,extraSessions:1,results},null,2))
console.log(JSON.stringify(results))
