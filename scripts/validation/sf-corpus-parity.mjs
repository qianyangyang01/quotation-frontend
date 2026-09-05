import fs from 'node:fs'
const [beforePath, afterPath, outputPath] = process.argv.slice(2)
if (!beforePath || !afterPath || !outputPath) throw new Error('Expected baseline JSON, candidate JSON, output JSON')
const before=JSON.parse(fs.readFileSync(beforePath,'utf8')), after=JSON.parse(fs.readFileSync(afterPath,'utf8'))
const unexpected=[], priceChanges=[]
function walk(a,b,path,sf){
 if(JSON.stringify(a)===JSON.stringify(b))return
 if(a && b && typeof a==='object' && typeof b==='object') {
  for(const key of new Set([...Object.keys(a),...Object.keys(b)])) {
   if(key==='parserVersion')continue
   if(sf && (/^source(?:OriginalRate|Discount|SettlementRate|PricingBasis)/.test(key)||key==='contentHash'))continue
   if(sf && key==='pricePerKg'){if(a[key]!==b[key])priceChanges.push({path:path+'.'+key,before:a[key],after:b[key]});continue}
   walk(a[key],b[key],path+'.'+key,sf)
  }
 }else unexpected.push({path,before:a,after:b})
}
for(const file of new Set([...Object.keys(before),...Object.keys(after)]))walk(before[file],after[file],file,file.includes('顺丰'))
const report={files:Object.keys(after).length,unexpected,priceChanges,passed:unexpected.length===0}
fs.writeFileSync(outputPath,JSON.stringify(report,null,2)+'\n')
console.log(JSON.stringify({files:report.files,unexpected:unexpected.length,priceChanges:priceChanges.length,passed:report.passed}))
if(!report.passed)process.exitCode=1
