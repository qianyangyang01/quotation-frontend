import { createHash } from 'node:crypto'
import { readFile, readdir } from 'node:fs/promises'
import { extname, basename, resolve } from 'node:path'

const imageRoot = resolve(process.argv[2] || 'public/purchase-images')
const authorityArgument = process.argv.slice(3).find(argument => !argument.startsWith('--'))
const authorityPath = authorityArgument ? resolve(authorityArgument) : ''
const files = (await readdir(imageRoot, { withFileTypes: true })).filter(entry => entry.isFile() && /^\.(png|jpe?g|gif|webp)$/i.test(extname(entry.name)))
const authority = authorityPath ? JSON.parse(await readFile(authorityPath, 'utf8')) : { purchaseProducts: [] }
const exactReferences = new Map()
for (const product of authority.purchaseProducts || []) {
  for (const [type, value] of [['product', product.productImage || product.image], ['physical', product.physicalImage]]) {
    const fileName = basename(String(value || '').split(/[?#]/)[0])
    if (fileName) exactReferences.set(fileName.toLowerCase(), { sku: String(product.sku || '').trim().toUpperCase(), imageType: type })
  }
}
const inventory = []
for (const file of files) {
  const bytes = await readFile(resolve(imageRoot, file.name))
  const exact = exactReferences.get(file.name.toLowerCase())
  inventory.push({ fileName: file.name, sizeBytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'), sku: exact?.sku || null, imageType: exact?.imageType || null, status: exact?.sku ? 'exact-match' : 'orphan-review' })
}
const mapped = inventory.filter(row => row.status === 'exact-match').length
const report = { generatedAt: new Date().toISOString(), root: imageRoot, total: inventory.length, mapped, orphaned: inventory.length - mapped, inventory }
process.stdout.write(`${JSON.stringify(process.argv.includes('--summary') ? { total: report.total, mapped: report.mapped, orphaned: report.orphaned } : report, null, 2)}\n`)
