import { execFile, spawn } from 'node:child_process';
import { promisify } from 'node:util';
import { writeFile } from 'node:fs/promises';
const run=promisify(execFile);
const prefix='quotation-interaction-perf-quotation-';
const samples=[];
let collecting=false;
async function sample(){
 if(collecting)return; collecting=true;
 try {
 const [stats,db]=await Promise.all([
 run('docker',['stats','--no-stream','--format','{{json .}}',prefix+'backend-1',prefix+'postgres-1',prefix+'redis-1']),
 run('docker',['exec',prefix+'postgres-1','psql','-U','quotation_app','-d','quotation_perf','-Atq','-c',"select json_build_object('connections',count(*),'active',count(*) filter(where state='active'),'lockWaits',count(*) filter(where wait_event_type='Lock'),'longestActiveSeconds',coalesce(max(extract(epoch from now()-query_start)) filter(where state='active'),0)) from pg_stat_activity where datname='quotation_perf'"])
 ]);
 samples.push({at:new Date().toISOString(),containers:stats.stdout.trim().split('\n').map(x=>JSON.parse(x)),db:JSON.parse(db.stdout)});
 }catch(e){samples.push({at:new Date().toISOString(),error:e.message})}finally{collecting=false}
}
await sample(); const timer=setInterval(sample,10000);
const child=spawn(process.execPath,['scripts/performance/quotation-load.mjs'],{stdio:'inherit',env:process.env});
const code=await new Promise(resolve=>child.on('exit',resolve)); clearInterval(timer); await sample();
await writeFile((process.env.PERF_OUTPUT || 'artifacts/performance-result.json').replace(/\.json$/,'-resources.json'),JSON.stringify(samples,null,2));process.exitCode=code;
