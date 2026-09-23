import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
const dir=process.env.PERF_OUTPUT_DIR||'artifacts/performance/mixed-final2'
const expected=[['warmup',50,300],['sustained',50,1800],['peak',100,300],['recovery',50,300],['soak',50,3600]]
const stages=[]
for(const [name,users,seconds] of expected){
 const report=JSON.parse(await readFile(dir+'/'+name+'.json','utf8'))
 assert.equal(report.users,users);assert.equal(report.seconds,seconds)
 assert(new Date(report.finishedAt)-new Date(report.startedAt)>=seconds*1000)
 const operations=Object.entries(report.operations)
 stages.push({name,users,seconds,requests:report.requests,httpFailures:report.httpFailures,businessFailures:report.businessFailures,expectedConflicts:report.expectedConflicts,exports:report.exports,pendingReviewWork:report.pendingReviewWork,passed:report.passed,thresholdFailures:report.thresholdFailures,
  slowestRead:operations.filter(([name])=>name.startsWith('GET ')&&!name.includes('logistics-import-')).sort((a,b)=>b[1].p95-a[1].p95)[0],
  save:report.operations['POST quotation-save'],claim:report.operations['PATCH review-claim'],complete:report.operations['PATCH review-complete'],
  imports:operations.filter(([name])=>name.includes('logistics-import-'))})
}
const resources=(await readFile(dir+'/resources.jsonl','utf8')).trim().split('\n').map(line=>JSON.parse(line))
assert(resources.length>=500,'Resource sampling must cover the full timed run')
assert(!resources.some(row=>row.error),'Missing resource samples must be investigated')
const mb=text=>{const [,n,unit]=text.match(/^([\d.]+)([A-Za-z]+)/);return Number(n)*({GiB:1024,MiB:1,KiB:1/1024,B:1/1024/1024}[unit]??NaN)}
const mean=values=>values.reduce((a,b)=>a+b,0)/values.length
const metrics={connectionsMax:Math.max(...resources.map(r=>r.db.connections)),connectionsLast:resources.at(-1).db.connections,lockWaitSamples:resources.filter(r=>r.db.lockWaits>0).length,longestQuerySeconds:Math.max(...resources.map(r=>r.db.longestActiveSeconds)),containers:{}}
for(const item of resources[0].containers){
 const samples=resources.map(row=>({at:row.at,...row.containers.find(c=>c.Name===item.Name)}))
 const end=new Date(samples.at(-1).at).getTime(),last=samples.filter(s=>new Date(s.at).getTime()>=end-600000),previous=samples.filter(s=>new Date(s.at).getTime()<end-600000&&new Date(s.at).getTime()>=end-1200000)
 metrics.containers[item.Name]={firstMiB:mb(samples[0].MemUsage),lastMiB:mb(samples.at(-1).MemUsage),maxMiB:Math.max(...samples.map(s=>mb(s.MemUsage))),last10MinuteMeanMiB:mean(last.map(s=>mb(s.MemUsage))),previous10MinuteMeanMiB:mean(previous.map(s=>mb(s.MemUsage))),maxCpuPercent:Math.max(...samples.map(s=>Number(s.CPUPerc.replace('%',''))))}
}
const summary={stages,resources:metrics,requests:stages.reduce((n,s)=>n+s.requests,0),passed:stages.every(s=>s.passed),note:'Independent authenticated HTTP sessions with real reads/writes; browser roles and pricing are separate real Chrome acceptance. Import timings are reported separately. RSS includes JVM heap retention and cache growth, not a proof against every possible leak.'}
await writeFile(dir+'/summary.json',JSON.stringify(summary,null,2));console.log(JSON.stringify(summary,null,2));if(!summary.passed)process.exitCode=1
