import fs from 'node:fs'
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { pathToFileURL } from 'node:url'
const source = process.cwd()
const refIndex = process.argv.indexOf('--ref')
const sourceRef = refIndex >= 0 ? process.argv[refIndex + 1] : ''
const readSource = file => sourceRef ? execFileSync('git', ['show', sourceRef + ':' + file], { encoding: 'utf8' }) : fs.readFileSync(path.join(source, file), 'utf8')
const rulesIndex = process.argv.indexOf('--rules')
if (rulesIndex < 0) throw new Error('--rules must point to a read-only logistics snapshot')
const rulesPath = process.argv[rulesIndex + 1]
const require = createRequire(source + '/package.json')
const ts = require('typescript')
const { parse, compileScript } = require('vue/compiler-sfc')
const vue = require('vue')
const outputDir = path.join(source, 'artifacts/performance/interaction-' + (sourceRef || 'candidate'))
fs.mkdirSync(outputDir, { recursive: true })
const out = pathToFileURL(outputDir + '/')
const transpile = text => ts.transpileModule(text, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
fs.writeFileSync(new URL('logistics.mjs', out), transpile(readSource('src/data/logistics.ts')))
const logistics = await import(new URL('logistics.mjs', out))
const script = readSource('src/views/QuotationSystemView.vue').split('<script setup lang="ts">')[1].split('</script>')[0]
const ast = ts.createSourceFile('view.ts', script, ts.ScriptTarget.Latest, true)
const names = ['quoteRegionForCountry', 'matchedLogistics', 'quantityCostBreakdown', 'excelQuoteRows']
const functions = ast.statements.filter(n => ts.isFunctionDeclaration(n) && names.includes(n.name?.text)).map(n => n.getText(ast)).join('\n')
let regionLookups = 0, billingCalls = 0
const deps = {
  ...logistics,
  logisticsQuoteRegions: c => { regionLookups++; return logistics.logisticsQuoteRegions(c) },
  calculateLogisticsFee: (...args) => { billingCalls++; return logistics.calculateLogisticsFee(...args) },
  quoteMode: { value: 'single' }, selectedQuoteRegions: { value: { '澳大利亚': '澳大利亚1区' } },
  customQuoteQuantity: { value: 5 }, purchaseRecords: { value: [] }, exchange: { value: { usd: 7 } },
  financePolicies: { value: [] }, logisticsRulesGeneration: vue.ref(1), logisticsRevision: vue.ref('snapshot'), financeAllowsLogisticsChannel: () => true,
  financeChannelKey: (id, r) => `${id}-${r.channelCode}`,
  singleActualWeight: (p, n = 1) => p.weight * n,
  normalizedBundleSets: n => Math.max(1,n), findPurchaseProduct: () => null,
  selectedGradeCoefficient: () => 1.2,
  taxResult: (c,p,n) => ({ totalUsd: n/7, taxUsd: 0 }),
  displayGrams: n => n*1000, formatLogisticsEta: () => '5～8 天',
}
const { quote, matched } = new Function(...Object.keys(deps), transpile(functions) + '\nreturn {quote: excelQuoteRows, matched: matchedLogistics}')(...Object.values(deps))
const countries = ['美国','英国','法国','澳大利亚','加拿大','新西兰','爱尔兰'].map((name,i)=>({name,code:['US','GB','FR','AU','CA','NZ','IE'][i],stage:'common',sortOrder:i}))
const product = { country:'美国',sku:'SYNTHETIC',weight:.2,purchase:10,purchaseFreightPerUnit:1,quantity:1,logisticsAttribute:'普货' }
const snapshot = JSON.parse(fs.readFileSync(rulesPath, 'utf8').replace(/^\uFEFF/, ''))
const rules = snapshot.map(rule => ({ ...rule, billingVerified: true, prices: rule.prices.filter(row => countries.some(c => c.name === row.areaName || c.code === row.countryCode) && logistics.isPriceRowEligible(row, ['普货'])) })).filter(rule => rule.prices.length)
logistics.replaceLogisticsRules(rules)
countries.forEach(c => { c.channelCount = matched(product, c.name).length })
let quoteCalls=[]
let quoted = country => quote(product, country)
if (!sourceRef) {
  fs.writeFileSync(new URL('countryQuotationCache.mjs', out), transpile(readSource('src/services/countryQuotationCache.ts')).replace(/from ['"]vue['"]/g, 'from ' + JSON.stringify(pathToFileURL(require.resolve('vue/dist/vue.cjs.js')).href)))
  const { createCountryQuotationCache } = await import(new URL('countryQuotationCache.mjs', out))
  quoted = createCountryQuotationCache(country => quote(product, country))
}
const quoteRegions = deps.selectedQuoteRegions
quoteRegions.value = vue.reactive(quoteRegions.value)

const inputSfc = readSource('src/components/quotation/QuotationCommonMatrix.vue').replace(/import QuoteTax(?:Meta|Legend) from [^\n]+/g, '')
const {descriptor}=parse(inputSfc.replace(/v-model(?:\.number)?="([^"]+)"/g, (_m, key) => `:value="${key}" @input="${key}=$event"`).replace("<script setup lang=\"ts\">", "<script setup lang=\"ts\">\nconst QuoteTaxMeta = { render: () => null }; const QuoteTaxLegend = { render: () => null };"))
let compiled=compileScript(descriptor,{id:'audit',inlineTemplate:true}).content
compiled=compiled.replace(/from ['"]vue['"]/g,`from ${JSON.stringify(pathToFileURL(require.resolve('vue/dist/vue.cjs.js')).href)}`)
compiled='const QuoteTaxMeta = { render: () => null }; const QuoteTaxLegend = { render: () => null };\n'+compiled
fs.writeFileSync(new URL('component.mjs',out),transpile(compiled))
const Component=(await import(new URL('component.mjs',out))).default
const node=(tag,text='')=>({tag,text,children:[],props:{},parent:null})
const renderer=vue.createRenderer({
  createElement:tag=>node(tag),createText:t=>node('#text',t),createComment:t=>node('#comment',t),
  insert(el,parent,anchor){if(el.parent){el.parent.children=el.parent.children.filter(x=>x!==el)}el.parent=parent;const i=parent.children.indexOf(anchor);if(i<0)parent.children.push(el);else parent.children.splice(i,0,el)},
  remove(el){el.parent.children=el.parent.children.filter(x=>x!==el)},setText(el,t){el.text=t},setElementText(el,t){el.text=t;el.children=[]},
  parentNode:el=>el.parent,nextSibling:el=>el.parent?.children[el.parent.children.indexOf(el)+1],patchProp:(el,k,p,n)=>el.props[k]=n,
})
const root=node('root')
const props=vue.reactive({countries,contextKey:'initial',adoptedCountry:'美国',adoptedRule:'',adoptedCarrier:'',exchangeRate:7,customQuantity:5,
quoteRowsForCountry:c=>{quoteCalls.push(c);return quoted(c)}})
const rows=[]
async function measure(label,fn){quoteCalls=[];regionLookups=0;billingCalls=0;const t=performance.now();await fn();await vue.nextTick();rows.push({label,ms:+(performance.now()-t).toFixed(2),quoteCalls:quoteCalls.length,countries:[...quoteCalls],regionLookups,billingCalls})}
await measure('mount',()=>renderer.createApp({render:()=>vue.h(Component,props)}).mount(root))
const all=el=>[el,...el.children.flatMap(all)]
await measure('channel-search-one-character',()=>all(root).find(n=>n.props['aria-label']==='搜索物流渠道').props['onInput']('测'))
await measure('country-switch-AU',()=>all(root).filter(n=>n.tag==='button'&&n.props.draggable)[3].props.onClick())
await measure('region-context-change-AU',()=>{deps.selectedQuoteRegions.value['澳大利亚']='澳大利亚2区';props.contextKey='region2'})
await measure('channel-search-second-character',()=>all(root).find(n=>n.props['aria-label']==='搜索物流渠道').props['onInput']('测试'))

for (let i=0;i<10;i++) {
 await measure('search-sample',()=>all(root).find(n=>n.props['aria-label']==='搜索物流渠道').props['onInput']('渠道'+i))
 await measure('switch-sample',()=>all(root).filter(n=>n.tag==='button'&&n.props.draggable)[i%2 ? 0 : 3].props.onClick())
 await measure('region-sample',()=>{quoteRegions.value['澳大利亚']='澳大利亚'+(i%4+1)+'区';props.contextKey='sample'+i})
}
const summary=Object.fromEntries(['search-sample','switch-sample','region-sample'].map(label=>{
 const samples=rows.filter(row=>row.label===label)
 const ms=samples.map(row=>row.ms).sort((a,b)=>a-b)
 return [label,{n:ms.length,p50:ms[Math.ceil(ms.length*.5)-1],p95:ms[Math.ceil(ms.length*.95)-1],max:ms.at(-1),billingCalls:samples.reduce((n,s)=>n+s.billingCalls,0)}]
}))
const parity = createHash('sha256')
let parityRows = 0
for (const weight of [.05,.2,.5,1,2]) {
  product.weight = weight
  for (const region of logistics.australiaQuoteRegions) {
    quoteRegions.value['澳大利亚'] = region
    for (const country of countries) {
      const values = quote(product, country.name)
      parityRows += values.length
      parity.update(JSON.stringify(values))
    }
  }
}
const result={priceFingerprint:parity.digest('hex'),parityRows,scope:'Node Vue custom renderer, production-scale read-only price snapshot; pricing functions unchanged; finance/tax/purchase dependencies stubbed; model DOM directives replaced by input handlers; not browser latency',sourceRef:sourceRef||'candidate',rules:rules.length,priceRows:rules.reduce((n,r)=>n+r.prices.length,0),summary,rows}
fs.writeFileSync(new URL('result.json',out),JSON.stringify(result,null,2))
console.log(JSON.stringify({rules:result.rules,priceRows:result.priceRows,summary,priceFingerprint:result.priceFingerprint,parityRows,initial:rows.slice(0,5)},null,2))
