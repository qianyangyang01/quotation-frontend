import fs from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
const bundled = createRequire('C:/Users/25490/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/package.json')
const { FileBlob, SpreadsheetFile } = await import(pathToFileURL(bundled.resolve('@oai/artifact-tool')).href)
const out = path.resolve('outputs/batch-price-export-local')
const book = await SpreadsheetFile.importXlsx(await FileBlob.load(path.join(out, 'local-download.xlsx')))
for(const [i, name] of ['批次汇总', '涨价明细', '降价明细', '不变明细', '其他变动'].entries()) {
 const image = await book.render({ sheetName: name, range: i ? 'A1:N10' : 'A1:F13', scale: 1.5, format: 'png' })
 await fs.writeFile(path.join(out, `excel-${i}.png`), new Uint8Array(await image.arrayBuffer()))
}
console.log((await book.inspect({kind:'table',range:'涨价明细!E6:M9',include:'values',tableMaxRows:4,tableMaxCols:9,maxChars:1800})).ndjson)
console.log('Downloaded XLSX reopened and all five sheets rendered.')
