// @vitest-environment happy-dom
import { readFileSync } from 'node:fs'
import { compileTemplate } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import QuotationPreviewSave from '@/components/quotation/QuotationPreviewSave.vue'
import { buildQuotationWeightSnapshot, parseSpecialPackagingGrams } from '@/data/quotationWeightSnapshot'
import { parseCommissionThreshold } from '@/services/quotationCommission'
import { customerGradeLabel } from '@/data/financeChannelPolicies'
import { quoteSheetRowKey } from '@/data/customerQuoteSheet'

// Compile the production loop and ref binding, and mount BOTH real child components.
// A mocked capturePrices object hides Vue's ref-in-v-for array behavior.
const source = readFileSync('src/views/QuotationSystemView.vue', 'utf8')
const loop = source.match(/<template v-for="p in products\.slice\(0,1\)" :key="p\.id">/)![0]
const binding = source.match(/<QuotationPreviewSave\s+((?::)?ref="[^"]+")/)![1]
const compiled = compileTemplate({ source: `${loop}<QuotationPreviewSave ${binding} v-bind="previewProps" :context-key="p.sku" @save="attemptSave" /></template>`, filename:'preview-ref.vue', id:'preview-ref', compilerOptions:{expressionPlugins:['typescript']} })
const render = new Function('require', 'exports', ts.transpileModule(compiled.code, { compilerOptions: { target: ts.ScriptTarget.ES2022, module:ts.ModuleKind.CommonJS } }).outputText+'\nreturn exports.render')(()=>Vue,{})
let app: Vue.App | undefined
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })
async function settle() { for (let i=0;i<6;i++) await Vue.nextTick() }
function button(text:string) { return Array.from(document.querySelectorAll('button')).find(b=>b.textContent?.includes(text))! }
async function input(label:string,value:string) {
  const field=document.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!
  field.value=value;field.dispatchEvent(new Event('input',{bubbles:true}));await settle()
}
function mount(mode:string) {
  const quotationPreview=Vue.ref<InstanceType<typeof QuotationPreviewSave>|null>(null)
  const products=Vue.ref([{id:1,sku:'SKU-A'}])
  const persisted=vi.fn(),errors:string[]=[]
  const previewProps=Vue.reactive({
    blockReason:'',saving:false,canRetry:false,retrying:false,
    rows:[{country:'美国',carrier:'燕文',rule:'rule',ruleId:1,channelKey:'route-a',channelCode:'A',transport:'channel',eta:'6-12 days',quote1:2,quote2:3,quote3:4,quoteCustom:5}],
    countries:[],salesperson:'QA',sourcePending:false,matrixModeLabel:mode,customerName:'QA',productName:'Test',sku:'SKU-A',customerGrade:'A',coefficient:1,customQuantity:4,unitLabel:'件',exchangeRate:6.7,
    primaryCountry:'美国',primaryCarrier:'燕文',primaryRule:'rule',primaryCnyPrice:13.4,primaryUsdPrice:2,
    calculatePrice:(_row:unknown,quantity:number)=>quantity+1,
  })
  app=Vue.createApp({components:{QuotationPreviewSave},setup:()=>({quotationPreview,products,previewProps,attemptSave:()=>{
    try { persisted(quotationPreview.value!.capturePrices()) }
    catch(error) { errors.push((error as Error).message) }
  }}),render})
  const host=document.createElement('div');document.body.append(host);app.mount(host)
  return {quotationPreview,products,persisted,errors,previewProps}
}
it.each(['国家渠道正在加载，请稍候','渠道加载失败，请点击添加渠道重试','财务设置已更新，请更新报价','无法确认物流正式版本，暂不能保存','佣金阈值必须大于0且不超过1'])('shows the real save blocker instead of a contradictory ready message: %s',async reason=>{
  const state=mount('common');state.previewProps.blockReason=reason;await settle()
  const footer=document.querySelector('footer')!
  expect(footer.textContent).toContain(reason)
  expect(footer.textContent).not.toContain('可以保存')
  expect(button('保存 1 张报价单').disabled).toBe(true)
  state.previewProps.blockReason='';await settle()
  expect(button('保存 1 张报价单').disabled).toBe(false)
  expect(footer.textContent).toContain('可以保存')
})
it('offers recovery without remounting the sheet and describes in-progress saves',async()=>{
  const state=mount('common');const mounted=state.quotationPreview.value
  state.previewProps.blockReason='渠道加载失败';state.previewProps.canRetry=true;await settle()
  expect(button('重新检查报价').disabled).toBe(false)
  state.previewProps.retrying=true;await settle()
  expect(button('正在重新检查').disabled).toBe(true)
  expect(document.querySelector('footer')?.textContent).not.toContain('可以保存')
  state.previewProps.retrying=false;state.previewProps.blockReason='';state.previewProps.saving=true;await settle()
  expect(document.querySelector('footer')?.textContent).toContain('正在校验并保存报价')
  expect(document.querySelector('footer')?.textContent).not.toContain('可以保存')
  expect(state.quotationPreview.value).toBe(mounted)
})
it.each(['common','specified','template'])('captures original and edited prices through the real %s preview save button',async mode=>{
  const state=mount(mode);await settle()
  await input('第 1 行第 2 列美元价格','2.70')
  button('新增列').click();await settle();await input('第 5 个价格列数量','8')
  await input('第 1 行第 5 列美元价格','8.80')
  button('保存 1 张报价单').click();await settle()
  expect(state.errors).toEqual([])
  expect(state.persisted).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({quantities:[1,2,3,4,8],rows:[expect.objectContaining({prices:[2,2.7,4,5,8.8],systemPrices:[2,3,4,5,9]})]}))
  expect(state.previewProps.rows[0].quote2).toBe(3)
})
it('uses the current mounted preview after product replacement, and blocks invalid edits',async()=>{
  const state=mount('common');await settle()
  const first=state.quotationPreview.value
  await input('第 1 行第 1 列美元价格','1.234')
  button('保存 1 张报价单').click();await settle()
  expect(state.persisted).not.toHaveBeenCalled();expect(state.errors[0]).toContain('两位小数')
  state.products.value=[{id:2,sku:'SKU-B'}];await settle()
  expect(state.quotationPreview.value).not.toBe(first)
  button('保存 1 张报价单').click();await settle()
  expect(state.persisted).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({rows:[expect.objectContaining({prices:[2,3,4,5]})]}))
})

it.each(['A', 'NEW'])('passes the mounted sheet prices and %s grade through the save function into the API payload',async(grade)=>{
  const state=mount('template');await settle()
  await input('第 1 行第 2 列美元价格','2.70')
  button('新增列').click();await settle();await input('第 5 个价格列数量','8')
  await input('第 1 行第 5 列美元价格','8.80')
  const script=source.split('<script setup lang="ts">')[1]!.split('</script>')[0]!
  const ast=ts.createSourceFile('view.ts',script,ts.ScriptTarget.Latest,true)
  const save=ast.statements.find(n=>ts.isFunctionDeclaration(n)&&n.name?.text==='save')!.getText(ast)
  const createQuotationRecord=vi.fn().mockResolvedValue({no:'QA-SAVE-REF'})
  const resetLocalDraft=vi.fn().mockResolvedValue(undefined),toast=vi.fn()
  const context={
    buildQuotationWeightSnapshot, parseSpecialPackagingGrams, specialPackagingGrams:{value:'10'}, specialPackagingError:{value:''}, singleBaseWeight:()=>.14,
    parseCommissionThreshold, commissionThreshold:{value:'0.95'}, commissionError:{value:''}, customerGradeLabel,nextTick:Vue.nextTick,quotationPreview:state.quotationPreview,createQuotationRecord,resetLocalDraft,toast,
    purchaseTaxBlockReason:{value:''},draftInitializationFailed:{value:false},financeSettingsAreHydrated:()=>true,
    products:{value:[{sku:'SKU-A',quantity:1,name:'QA',country:'美国',rule:'rule',purchaseBaseUnitPrice:2,purchaseInvoiceRatePercent:0,purchase:2,purchaseFreightPerUnit:0}]},
    customerOperation:{value:{configured:true,snapshot:undefined}},customerName:{value:'QA'},productCategory:{value:'日用品'},savedQuoteRows:{value:state.previewProps.rows.map(row=>({...row,taxConfigured:true}))},
    hasQuotationProduct:()=>true,quoteMode:{value:'single'},bundleItems:{value:[]},quoteMatrixMode:{value:'template'},activeTemplateSnapshot:{value:{id:'template-a',name:'QA'}},
    buildQuoteOptions:()=>[{id:'option-a',quoteSheetKey:quoteSheetRowKey(state.previewProps.rows[0]!)}],
    selectedQuoteSummary:()=>({systemQuoteUsd:2,systemQuoteCny:13.4,totalCostCny:10}),
    activePurchaseSkus:()=>[],logisticsRevision:{value:'revision-a'},currentSalespersonName:{value:'QA'},currentSalespersonAccount:{value:'QA'},
    preferredQuotationImage:()=>'',selectedCustomerGrade:{value:grade},monthlySalesEstimate:{value:10},exchange:{value:{usd:6.7}},customQuoteQuantity:{value:4},
    draftVersion:{value:-1},draftUpdatedAt:{value:''},
    appliedFinanceVersions:{'country-classification':3,'channel-policies':43,'customer-grades':14,'exchange-rate':7,'tax-settings':28,'surcharge-settings':12,'customer-operation-fees':4},
  }
  const run=new Function(...Object.keys(context),ts.transpileModule(save,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText+'\nreturn save')(...Object.values(context))
  await run()
  expect(createQuotationRecord).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
    commissionThreshold:0.95, systemQuoteUsd:2, financeVersions:context.appliedFinanceVersions,
    weightSnapshot: buildQuotationWeightSnapshot([{sku:'SKU-A',quantityPerSet:1,baseWeightKg:.14}],10,[1,2,3,4,8]),
    customerQuote:{quantities:[1,2,3,4,8],rows:[{optionId:'option-a',prices:[2,2.7,4,5,8.8]}]},
    systemQuantityQuotes:{quantities:[1,2,3,4,8],rows:[{optionId:'option-a',prices:[2,3,4,5,9]}]},
  }))
  expect(resetLocalDraft).toHaveBeenCalledOnce()
  expect(toast).toHaveBeenCalledWith(expect.stringContaining('报价已保存：QA-SAVE-REF'))
})
