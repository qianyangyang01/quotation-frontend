import { createHash } from 'node:crypto'
import { promises as fs } from 'node:fs'
import path from 'node:path'

const [sourceDir, zipPath, outputPath] = process.argv.slice(2)
if (!sourceDir || !zipPath || !outputPath) {
  throw new Error('Usage: node scripts/build-sumao-source-manifest.mjs <sourceDir> <zipPath> <outputPath>')
}

async function sha256(filePath) {
  const data = await fs.readFile(filePath)
  return createHash('sha256').update(data).digest('hex')
}

async function walk(directory) {
  const result = []
  for (const entry of await fs.readdir(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, entry.name)
    if (entry.isDirectory()) result.push(...await walk(absolute))
    else if (entry.isFile() && entry.name.toLowerCase().endsWith('.xlsx')) result.push(absolute)
  }
  return result
}

const files = (await walk(sourceDir)).sort((left, right) => left.localeCompare(right, 'zh-CN'))
const entries = []
for (const absolute of files) {
  const stat = await fs.stat(absolute)
  entries.push({
    relativePath: path.relative(sourceDir, absolute).split(path.sep).join('/'),
    fileName: path.basename(absolute),
    bytes: stat.size,
    sha256: await sha256(absolute),
  })
}
const duplicateHashes = Object.entries(Object.groupBy(entries, (entry) => entry.sha256))
  .filter(([, values]) => values.length > 1)
  .map(([hash, values]) => ({ hash, files: values.map((entry) => entry.relativePath) }))
const manifest = {
  schemaVersion: 1,
  sourceType: 'sumao-logistics-zip',
  generatedAt: new Date().toISOString(),
  sourceDirectory: sourceDir,
  zipFile: path.basename(zipPath),
  zipBytes: (await fs.stat(zipPath)).size,
  zipSha256: await sha256(zipPath),
  expectedFiles: 66,
  actualFiles: entries.length,
  sourceBytes: entries.reduce((sum, entry) => sum + entry.bytes, 0),
  duplicateContentGroups: duplicateHashes,
  files: entries,
}
await fs.mkdir(path.dirname(outputPath), { recursive: true })
await fs.writeFile(outputPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
if (entries.length !== 66 || duplicateHashes.length > 0) process.exitCode = 2
