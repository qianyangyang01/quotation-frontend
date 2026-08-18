import { createReadStream, existsSync, statSync } from 'node:fs'
import { createServer } from 'node:http'
import { extname, join, normalize } from 'node:path'

const root = join(process.cwd(), 'dist')
const port = Number(process.env.PORT || 5174)
const types = {
  '.css': 'text/css; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.webp': 'image/webp',
  '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2',
}

createServer((request, response) => {
  const pathname = decodeURIComponent(new URL(request.url || '/', 'http://127.0.0.1').pathname)
  const relative = normalize(pathname).replace(/^([/\\])+/, '')
  let file = join(root, relative)
  if (!file.startsWith(root) || !existsSync(file) || statSync(file).isDirectory()) file = join(root, 'index.html')
  response.setHeader('Content-Type', types[extname(file)] || 'application/octet-stream')
  response.setHeader('Cache-Control', extname(file) === '.html' ? 'no-cache' : 'public, max-age=3600')
  createReadStream(file).on('error', () => {
    response.statusCode = 500
    response.end('Server error')
  }).pipe(response)
}).listen(port, '127.0.0.1', () => {
  console.log(`Milano quotation system: http://127.0.0.1:${port}/quotation`)
})
