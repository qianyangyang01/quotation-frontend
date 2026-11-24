// Build a new local acceptance fixture; never change the historical package or production.
import { readFile, writeFile, copyFile, mkdir } from 'node:fs/promises'
import { resolve, join } from 'node:path'
import Decimal from 'decimal.js'

const [sourceArg, targetArg] = process.argv.slice(2)
if (!sourceArg || !targetArg) throw new Error('Usage: node prepare-baizhou-actual-fixture.mjs HISTORICAL_PACKAGE NEW_FIXTURE_DIRECTORY')
const source = resolve(sourceArg), target = resolve(targetArg)
if (source === target || target.startsWith(source + '/') || target.startsWith(source + '\\')) throw new Error('Use a separate fixture directory')
const read = async name => JSON.parse((await readFile(join(source, name), 'utf8')).replace(/^\uFEFF/, ''))
const expected = await read('expected.json'), cases = await read('billing-cases.json')
for (const row of expected.rows) row.pricingModel = 'per-kg-1g'
for (const sample of cases.samples) {
  const weight = Decimal.max(sample.weightKg, '.001').toDecimalPlaces(3, Decimal.ROUND_CEIL)
  const matches = expected.rows.filter(row => row.countryCode === sample.country && weight.gte(row.weightFromKg) && weight.lte(row.weightToKg))
  if (matches.length !== 1) throw new Error('Ambiguous fixture weight: ' + JSON.stringify(sample))
  sample.total = weight.times(matches[0].pricePerKg).plus(matches[0].registrationFee).toDecimalPlaces(2, Decimal.ROUND_HALF_UP).toNumber()
}
await mkdir(target, { recursive: true })
await copyFile(join(source, 'source.xlsx'), join(target, 'source.xlsx'))
await writeFile(join(target, 'expected.json'), JSON.stringify(expected, null, 2))
await writeFile(join(target, 'billing-cases.json'), JSON.stringify(cases, null, 2))
console.log(JSON.stringify({ target, sha256: expected.sha256, rows: expected.rows.length, boundaries: cases.samples.length, rejected: cases.rejected.length }))
