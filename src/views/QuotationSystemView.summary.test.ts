import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { describe, it, expect } from 'vitest'

const source = readFileSync(new URL('./QuotationSystemView.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
const ast = ts.createSourceFile('view.ts', source, ts.ScriptTarget.Latest, true)
const fn = ast.statements.find(n => ts.isFunctionDeclaration(n) && n.name?.text === 'selectedQuoteSummary')!
const js = ts.transpileModule(fn.getText(ast), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText
const option = (country = '爱尔兰', isPrimary = false) => ({ country, carrier: '燕文', channel: '追踪', rule: '规则', quoteRegion: '3区', channelKey: '595::燕文::IE', isPrimary, quoteCustomUsd: 82 })
function execute(options: ReturnType<typeof option>[], price: unknown = { quoteUsd: 19.75, quoteCny: 132.33, cost: 108.74 }) {
  const calls: unknown[][] = []
  const run = new Function('logisticsRuleByName', 'billingQuoteRegion', 'savedQuoteRows', 'products', 'quantityCostBreakdown', js + ';return selectedQuoteSummary')(() => ({}), (_rule: unknown, _country: string, region: string) => region, { value: options }, { value: [{ country: '美国' }] }, (...args: unknown[]) => { calls.push(args); return price })
  return { result: run(options), calls }
}
describe('formal quotation selected summary', () => {
  it('uses the selected country and one-unit cost instead of the default or custom quantity', () => {
    const options = [option()]
    const { result, calls } = execute(options)
    expect(result).toEqual({ country: '爱尔兰', carrier: '燕文', channel: '追踪', rule: '规则', systemQuoteUsd: 19.75, systemQuoteCny: 132.33, totalCostCny: 108.74 })
    expect(calls[0]!.slice(1)).toEqual(['规则', 1, '爱尔兰', '燕文', '3区', '595::燕文::IE'])
    expect(options[0]!.isPrimary).toBe(true)
  })
  it('preserves an explicitly selected primary and makes it unique', () => {
    const options = [option(), option('澳大利亚', true), option('美国', true)]
    expect(execute(options).result.country).toBe('澳大利亚')
    expect(options.map(o => o.isPrimary)).toEqual([false, true, false])
  })
  it('blocks unavailable and non-finite prices without falling back', () => {
    expect(execute([]).result).toBeNull()
    expect(execute([option()], null).result).toBeNull()
    expect(execute([option()], { quoteUsd: NaN, cost: 1 }).result).toBeNull()
  })
  it('all three modes share the corrected save payload', () => {
    const save = ast.statements.find(n => ts.isFunctionDeclaration(n) && n.name?.text === 'save')!.getText(ast)
    expect(save).toContain('selectedQuoteSummary(quoteOptions)')
    expect(save).toContain('...summary')
    expect(save).not.toContain('systemQuoteUsd: taxResult(p.country')
  })
})
