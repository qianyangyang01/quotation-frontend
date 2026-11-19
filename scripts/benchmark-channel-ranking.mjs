import fs from 'node:fs'
import {spawnSync} from 'node:child_process'
const fixture=fs.readFileSync('backend/src/test/java/com/milano/quotation/quote/QuotationChannelRankingPostgresTest.java','utf8').match(/jdbc.execute\("""([\s\S]*?)"""\)/)[1]
function sql(text){const r=spawnSync('docker',['exec','-i','quotation-channel-ranking-db-20261119','psql','-U','postgres','-d','ranking_perf','-v','ON_ERROR_STOP=1','-qAt'],{input:text,encoding:'utf8',maxBuffer:30*1024*1024});if(r.status)throw new Error(r.stderr);return r.stdout}
sql(fixture+ `
insert into quotation_record select md5('load'||i)::uuid,
(select payload from quotation_record where id=md5('q1')::uuid),'2026-11-19T02:00:00Z','active'
from generate_series(1,10000)i;
analyze;
`)
const params={start:"'2026-11-18T16:00:00Z'",end:"'2026-11-19T16:00:00Z'",countries:`'{"US":"US","美国":"US","德国":"DE"}'`,country:"''",salesperson:"''",category:"''",keyword:"''"}
const query=fs.readFileSync('backend/src/main/resources/analytics/channel-ranking.sql','utf8').replace(/(?<!:):([a-zA-Z]+)/g,(_,key)=>{if(!(key in params))throw new Error(key);return params[key]})
const results={}
for(const jit of ['on','off'])results[jit]=JSON.parse(sql(`begin read only;set local jit=${jit};EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) ${query};rollback;`))[0]
fs.mkdirSync('outputs/channel-ranking',{recursive:true});fs.writeFileSync('outputs/channel-ranking/query-plans.json',JSON.stringify(results,null,2))
for(const [jit,plan]of Object.entries(results)){const nodes=[];function walk(p){nodes.push({type:p['Node Type'],time:p['Actual Total Time'],loops:p['Actual Loops'],rows:p['Actual Rows']});for(const c of p.Plans||[])walk(c)}walk(plan.Plan);console.log(JSON.stringify({jit,executionMs:plan['Execution Time'],planningMs:plan['Planning Time'],jitDetails:plan.JIT,top:nodes.sort((a,b)=>b.time*b.loops-a.time*a.loops).slice(0,8)}))}
