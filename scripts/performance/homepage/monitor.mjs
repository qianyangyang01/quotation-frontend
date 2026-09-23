import {execFile,spawn} from 'node:child_process'
import {promisify} from 'node:util'
import {mkdir,appendFile,writeFile} from 'node:fs/promises'
const run=promisify(execFile),prefix='quotation-homepage-acceptance-20260923-'
const output=process.env.PERF_OUTPUT_DIR||'artifacts/performance/mixed'
await mkdir(output,{recursive:true});await writeFile(output+'/resources.jsonl','')
let collecting=false
async function sample(){
  if(collecting)return;collecting=true
  try{
    const [stats,db]=await Promise.all([
      run('docker',['stats','--no-stream','--format','{{json .}}',...['quotation-backend','db','redis','parser','minio'].map(n=>prefix+n+'-1')]),
      run('docker',['exec',prefix+'db-1','psql','-X','-U','quotation_app','-d','quotation_perf','-Atq','-c',"select json_build_object('connections',count(*),'active',count(*) filter(where state='active'),'lockWaits',count(*) filter(where wait_event_type='Lock'),'longestActiveSeconds',coalesce(max(extract(epoch from now()-query_start)) filter(where state='active'),0)) from pg_stat_activity where datname='quotation_perf'"]),
    ])
    await appendFile(output+'/resources.jsonl',JSON.stringify({at:new Date().toISOString(),containers:stats.stdout.trim().split('\n').map(x=>JSON.parse(x)),db:JSON.parse(db.stdout)})+'\n')
  }catch(e){await appendFile(output+'/resources.jsonl',JSON.stringify({at:new Date().toISOString(),error:e.message})+'\n')}
  finally{collecting=false}
}
await sample();const timer=setInterval(sample,10000)
const child=spawn(process.execPath,['scripts/performance/homepage/mixed-load.mjs'],{stdio:'inherit',env:process.env})
const code=await new Promise(resolve=>child.on('exit',resolve));clearInterval(timer)
while(collecting)await new Promise(resolve=>setTimeout(resolve,50))
await sample();process.exitCode=code
