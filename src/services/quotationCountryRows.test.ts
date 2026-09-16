import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { expect, it, vi } from 'vitest'
import { reactive, ref } from 'vue'
import { createCountryQuotationCache } from './countryQuotationCache'
import { createCountryQuotationGeneration } from './countryQuotationGeneration'
import { isAustraliaQuoteCountry, replaceLogisticsCountryCatalog } from '@/data/logistics'

// Exercise the view's actual callback/cache wiring, not a replacement callback
// supplied by the test (which would miss the previous specified/template bypass).
const source = readFileSync(new URL('../views/QuotationSystemView.vue', import.meta.url), 'utf8')
  .split('<script setup lang="ts">')[1]!.split('</script>')[0]!
const ast = ts.createSourceFile('quotation.ts', source, ts.ScriptTarget.Latest, true)
const names = new Set(['countryQuoteRows', 'regionalQuoteRows', 'allRegionalQuoteRows', 'expandedCountryQuoteRows', 'activeRegionalQuoteRows'])
const declarations = ast.statements.filter(node => ts.isFunctionDeclaration(node)
  ? names.has(node.name?.text || '')
  : ts.isVariableStatement(node) && node.declarationList.declarations.some(declaration => names.has(declaration.name.getText(ast))))
const script = ts.transpile(declarations.map(node => node.getText(ast)).join('\n'), { target: ts.ScriptTarget.ES2022 })

function setup() {
  replaceLogisticsCountryCatalog([{ code: 'US', name: '美国' }, { code: 'NL', name: '荷兰' }, { code: 'AU', name: '澳大利亚' }])
  const global = ref(0)
  const generation = createCountryQuotationGeneration(global)
  const inputs = reactive({ weight: 1, finance: 10, region: '澳大利亚1区' })
  const excelQuoteRows = vi.fn((_product: unknown, country: string, region = inputs.region) => [
    { country, quoteRegion: region, quote1: inputs.weight * inputs.finance, carrier: 'A', transport: 'B' },
  ])
  const state = {
    products: ref([{}]), logisticsRevision: ref('v1'), countryRulesGeneration: generation,
    createCountryQuotationCache, isAustraliaQuoteCountry, excelQuoteRows,
    logisticsQuoteRegions: (country: string) => country === '澳大利亚' ? ['澳大利亚1区', '澳大利亚2区'] : [],
  }
  const get = new Function('state', `with(state) { ${script}; return { common: countryQuoteRows, expanded: activeRegionalQuoteRows }; }`)(state) as {
    common: (country: string) => unknown[]; expanded: (country: string) => Array<{ quoteRegion: string; quote1: number }>
  }
  return { get, excelQuoteRows, inputs, generation, global, state }
}

it('calculates an unzoned country only once across 100 specified/template reads and the common view', () => {
  const { get, excelQuoteRows, inputs, generation, global } = setup()
  const us = get.expanded('美国')
  for (let i = 0; i < 100; i++) expect(get.expanded('美国')).toBe(us)
  expect(get.common('美国')).toBe(us)
  expect(excelQuoteRows).toHaveBeenCalledTimes(1)
  get.expanded('荷兰')
  generation.invalidate(['NL'])
  expect(get.expanded('美国')).toBe(us)
  expect(get.expanded('荷兰')[0]?.quote1).toBe(10)
  expect(excelQuoteRows).toHaveBeenCalledTimes(3)
  inputs.weight = 2
  expect(get.expanded('美国')[0]?.quote1).toBe(20)
  inputs.finance = 12
  expect(get.expanded('美国')[0]?.quote1).toBe(24)
  const current = get.expanded('美国')
  global.value++
  expect(get.expanded('美国')).not.toBe(current)
})

it('keeps every AU region in specified/template mode and only the selected region in common mode', () => {
  const { get, excelQuoteRows, inputs, state } = setup()
  const all = get.expanded('澳大利亚')
  expect(all.map(row => row.quoteRegion)).toEqual(['澳大利亚1区', '澳大利亚2区'])
  get.expanded('澳大利亚')
  expect(excelQuoteRows).toHaveBeenCalledTimes(2)
  expect(get.common('澳大利亚')).toEqual([expect.objectContaining({ quoteRegion: '澳大利亚1区' })])
  inputs.region = '澳大利亚2区'
  expect(get.common('澳大利亚')).toEqual([expect.objectContaining({ quoteRegion: '澳大利亚2区' })])
  expect(get.expanded('澳大利亚')).toBe(all)
  state.logisticsRevision.value = 'v2'
  expect(get.expanded('澳大利亚')).not.toBe(all)
})
