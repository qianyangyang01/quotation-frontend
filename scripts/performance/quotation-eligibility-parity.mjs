import fs from 'node:fs'
import ts from 'typescript'
import { pathToFileURL } from 'node:url'
import path from 'node:path'
const input = process.argv[2]
if (!input) throw new Error('Pass an isolated or read-only rule snapshot JSON path')
const directory = path.resolve('artifacts/performance/eligibility-parity')
fs.mkdirSync(directory, { recursive: true })
const compile = source => ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
const modulePath = path.join(directory, 'logistics.mjs')
fs.writeFileSync(modulePath, compile(fs.readFileSync('src/data/logistics.ts', 'utf8')))
const logistics = await import(pathToFileURL(modulePath))
const rules = JSON.parse(fs.readFileSync(input, 'utf8').replace(/^\uFEFF/, ''))
logistics.replaceLogisticsRules(rules)
const source = fs.readFileSync('src/data/financeChannelPolicies.ts', 'utf8')
const ast = ts.createSourceFile('finance.ts', source, ts.ScriptTarget.Latest, true)
const names = ['financeAllowsLogisticsChannel', 'financeChannelKey', 'channelsAvailableForCountry', 'describeAustraliaQuoteRegions']
const functions = ast.statements.filter(n => ts.isFunctionDeclaration(n) && names.includes(n.name?.text)).map(n => n.getText(ast)).join('\n')
const api = new Function(...Object.keys(logistics), compile(functions).replace(/^export /gm, '') + '\nreturn {financeAllowsLogisticsChannel,financeChannelKey,channelsAvailableForCountry}')(...Object.values(logistics))
const countries = [...new Set(rules.flatMap(rule => rule.prices.flatMap(row => [row.areaName, row.countryCode])))].filter(Boolean)
const keys = rules.flatMap(rule => rule.relations.map(relation => api.financeChannelKey(rule.id, relation)))
const policies = [{ category: '普货', enabled: true, countryRules: countries.map(country => ({ country, allowedChannels: keys })) }]
let comparisons = 0
for (const country of countries) {
  const expected = new Set(api.channelsAvailableForCountry(country).map(option => option.key))
  for (const rule of rules) for (const relation of rule.relations) {
    const actual = api.financeAllowsLogisticsChannel(policies, '普货', country, rule.id, relation)
    if (actual !== expected.has(api.financeChannelKey(rule.id, relation))) throw new Error(`Eligibility mismatch: ${country}/${rule.id}`)
    comparisons++
  }
}
const result = { rules: rules.length, countries: countries.length, comparisons, mismatches: 0 }
fs.writeFileSync(path.join(directory, 'result.json'), JSON.stringify(result, null, 2))
process.stdout.write(JSON.stringify(result))
