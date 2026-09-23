import { spawn, execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { writeFile } from 'node:fs/promises'
const exec=promisify(execFile),samples=[]
const prefix='quotation-sync-stress-20260923'
let busy=false
async function capture(){
  if(busy)return;busy=true
  try {
    const [stats,db]=await Promise.all([
      exec('docker',['stats','--no-stream','--format','{{json .}}',prefix+'-backend-1',prefix+'-db-1',prefix+'-redis-1']),
      exec('docker',['exec',prefix+'-db-1','psql','-U','quotation_app','-d','quotation_perf','-At','-c',"select json_build_object('connections',count(*),'active',count(*) filter(where state='active'),'lockWaits',count(*) filter(where wait_event_type='Lock')) from pg_stat_activity where datname='quotation_perf'"])
    ])
    samples.push({at:new Date().toISOString(),containers:stats.stdout.trim().split('\n').map(x=>JSON.parse(x)),db:JSON.parse(db.stdout)})
  }catch(e){samples.push({at:new Date().toISOString(),error:e.message})}finally{busy=false}
}
await capture();const timer=setInterval(()=>void capture(),5000)
const child=spawn(process.execPath,['scripts/performance/purchase-review-stress.mjs'],{stdio:'inherit',env:process.env})
const code=await new Promise(resolve=>child.once('exit',resolve));clearInterval(timer)
while(busy)await new Promise(r=>setTimeout(r,100))
await capture();await writeFile((process.env.PERF_OUTPUT||'artifacts/sync-stress/load.json').replace('.json','-resources.json'),JSON.stringify(samples,null,2))
process.exitCode=code||0
