import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { expect, it } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from '@/data/logistics'
import { singleActualWeight, bundleGoodsWeight, normalizedQuoteQuantity } from '@/services/quotationCalculator'
import { productDecimal, sumDecimal } from '@/services/quotationDecimal'
import { calculateFinanceQuoteFees } from '@/data/financeSurchargeSettings'
import { EU_YUNEXPRESS_CHC_CHANNEL_CODES } from '@/data/euYunExpressTax'

const source = readFileSync('src/views/QuotationSystemView.vue', 'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
const ast = ts.createSourceFile('view.ts', source, ts.ScriptTarget.Latest, true)
const fn = ast.statements.find(n => ts.isFunctionDeclaration(n) && n.name?.text === 'quantityCostBreakdown')!.getText(ast)
const key = `592::云途::${EU_YUNEXPRESS_CHC_CHANNEL_CODES[0]}`
const rule = { id: 592, name: 'CHC', status: '启用', relations: [], prices: [{
  areaName: '德国', countryCode: 'DE', weightFromKg: 0, weightToKg: 30,
  pricePerKg: 10, registrationFee: 2, surcharge: 0, fuelSurchargeRate: 0,
  prohibitedMarks: '', allowedMarks: '', zoneName: '',
}] } as unknown as LogisticsRule
const product = { sku:'A', country:'德国', logisticsAttribute:'普货', quantity:1, netWeight:0.1, weightSource:'purchase' as const, manualWeight:0,
  volumetricEnabled:false, packageLengthCm:0,packageWidthCm:0,packageHeightCm:0,volumeDivisor:8000,purchase:10,purchaseFreightPerUnit:0 }
const bundle = [
  {sku:'A',quantityPerSet:2,weightKg:0.1,customWeightKg:null,purchaseUnitPrice:10,purchaseFreightPerUnit:0},
  {sku:'B',quantityPerSet:1,weightKg:0.2,customWeightKg:null,purchaseUnitPrice:10,purchaseFreightPerUnit:0},
]
const empty = { countries:[],providers:[],updatedAt:'' }

it.each(['single','bundle'])('uses the actual %s order including packaging for every quantity, without scaling the fixed duty or grade', mode => {
  const context = {
    purchaseTaxBlockReason:{value:''},logisticsRuleForChannel:()=>rule,normalizedBundleSets:normalizedQuoteQuantity,
    quoteMode:{value:mode},bundleGoodsWeight:(q:number)=>bundleGoodsWeight(bundle,q),singleActualWeight,calculateLogisticsFee,
    bundlePurchaseCost:(q:number)=>30*q,bundleDomesticFreight:()=>0,sumDecimal,productDecimal,
    findPurchaseProduct:()=>undefined,purchaseRecords:{value:[]},selectedGradeCoefficient:()=>2,
    taxResult:(country:string,provider:string,cost:number,_name:string,channelKey:string,weightKg:number)=>calculateFinanceQuoteFees(empty,empty,country,provider,cost/6.7,channelKey,{weightKg,eurUsd:1.2}),
    quoteCnyFromUsd:(usd:number,rate:number)=>usd*rate,exchange:{value:{usd:6.7}},
  }
  const calculate = new Function(...Object.keys(context), ts.transpileModule(fn,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText+'\nreturn quantityCostBreakdown')(...Object.values(context))
  for (const quantity of [1,2,3,5,8,10,25]) {
    const result = calculate(product,'CHC',quantity,'德国','云途','',key)
    const expectedGrams = (mode === 'single' ? 104 : 416)*quantity
    expect(result.tax.calculation.weightKg).toBe(expectedGrams/1000)
    expect(result.tax.calculation.taxEur).toBeCloseTo(expectedGrams/1000*1.5+0.6,12)
    expect(result.tax.taxUsd).toBe(Number(((expectedGrams/1000*1.5+0.6)*1.2).toFixed(2)))
  }
})
