import fs from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'
const bundled = createRequire('C:/Users/25490/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/package.json')
const { chromium } = bundled('playwright')
const output = path.resolve('outputs/batch-price-export-local')
await fs.mkdir(output, { recursive: true })
const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
const page = await browser.newPage({ viewport: { width: 1712, height: 960 }, acceptDownloads: true })
const errors = [], mutations = []
page.on('pageerror', error => errors.push(error.message))
page.on('request', req => { if (!['GET', 'HEAD', 'OPTIONS'].includes(req.method())) mutations.push(`${req.method()} ${req.url()}`) })
try {
 const response = await page.goto('http://127.0.0.1:5226/dev/batch-price-export/index.html')
 if (response.status() !== 200) throw Error('Preview unavailable')
 await page.getByRole('button', { name: '下载批次明细', exact: true }).waitFor()
 await page.screenshot({ path: path.join(output, '01-local-page.png') })
 // A screen filter must not reduce the downloaded scope.
 await page.getByRole('textbox', { name: '搜索更新渠道' }).fill('美国')
 await page.getByRole('button', { name: '下载批次明细', exact: true }).click()
 const dialog = page.getByRole('dialog', { name: '下载批次明细', exact: true })
 await dialog.getByText('共 12 条重量段记录').waitFor()
 await page.screenshot({ path: path.join(output, '02-download-dialog.png') })
 const downloaded = page.waitForEvent('download')
 await dialog.getByRole('button', { name: '下载 Excel', exact: true }).click()
 const download = await downloaded
 await download.saveAs(path.join(output, 'local-download.xlsx'))
 await dialog.getByRole('button', { name: '关闭', exact: true }).click()
 await page.setViewportSize({ width: 390, height: 844 })
 await page.getByRole('button', { name: '下载批次明细', exact: true }).click()
 await page.getByRole('dialog', { name: '下载批次明细', exact: true }).getByText('共 12 条重量段记录').waitFor()
 await page.screenshot({ path: path.join(output, '03-mobile-dialog.png') })
 if (errors.length || mutations.length) throw Error(JSON.stringify({ errors, mutations }))
 await fs.writeFile(path.join(output, 'browser-check.json'), JSON.stringify({ http: 200, count: 12, screenFilter: '美国', downloaded: download.suggestedFilename(), errors, mutations }, null, 2))
 console.log('Browser download verified: 12 rows despite US screen filter; no writes; no page errors.')
} finally { await browser.close() }
