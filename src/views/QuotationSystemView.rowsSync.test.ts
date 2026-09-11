import {readFileSync} from 'node:fs'
import ts from 'typescript'
import {expect,it} from 'vitest'
import {quotationRowsSignature} from '@/services/quotationRowsSignature'
import type {QuotationMatrixRow} from '@/components/quotation/types'

const source=readFileSync(new URL('./QuotationSystemView.vue',import.meta.url),'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
const ast=ts.createSourceFile('view.ts',source,ts.ScriptTarget.Latest,true)
const base=()=>({country:'美国',channelKey:'old',rule:'规则',carrier:'物流',transport:'渠道',quote1:10,quote2:20,quote3:null,quoteCustom:null,freight:5,totalCostCny:50} as QuotationMatrixRow)
function harness(mode:string){
  const name=`update${mode[0]!.toUpperCase()+mode.slice(1)}Quotes`
  const node=ast.statements.find(n=>ts.isFunctionDeclaration(n)&&n.name?.text===name)!
  const js=ts.transpile(node.getText(ast),{target:ts.ScriptTarget.ES2022})
  const held={value:[base()]}
  const state={modeSelections:{value:{}},selectionFromRows:(rows:QuotationMatrixRow[])=>rows.map(row=>({channelKey:row.channelKey})),matrixRowsSignature:quotationRowsSignature,commonQuoteRows:held,specifiedQuoteRows:held,templateQuoteRows:held}
  const run=new Function('state',`with(state){${js};return ${name}}`)(state) as (rows:QuotationMatrixRow[])=>void
  return {held,run}
}
for(const mode of ['common','specified','template']) {
  it.each<Partial<QuotationMatrixRow>>([
    {channelKey:'new'}, {freight:5.01,totalCostCny:50.01,profitCny:10},
    {available:false,availabilityMessage:'属性不支持'}, {quantityMessages:{'3':'体积超限'}},
    {eta:'8～10天'}, {surchargeUsd:2,tax1Usd:1},
  ])(`${mode} retains current quote metadata even when rounded prices do not change: %j`,patch=>{
    const {held,run}=harness(mode);const next={...base(),...patch};run([next]);expect(held.value[0]).toBe(next)
  })
  it(`${mode} avoids replacing unchanged snapshots solely due to property order`,()=>{
    const {held,run}=harness(mode);const original=held.value
    const reordered=Object.fromEntries(Object.entries(base()).reverse()) as QuotationMatrixRow
    run([reordered]);expect(held.value).toBe(original)
  })
}
it('compares null and zero distinctly and ignores nested property ordering',()=>{
  expect(quotationRowsSignature([{...base(),quote3:null}])).not.toBe(quotationRowsSignature([{...base(),quote3:0}]))
  expect(quotationRowsSignature([{...base(),quantityMessages:{a:'one',b:'two'}}])).toBe(quotationRowsSignature([{...base(),quantityMessages:{b:'two',a:'one'}}]))
})