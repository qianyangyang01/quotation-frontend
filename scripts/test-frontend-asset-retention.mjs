import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { randomUUID } from 'node:crypto'

// Disposable local Docker resources only; no production containers or volumes are used.
const id = `quotation-assets-test-${randomUUID().slice(0, 8)}`
const dir = mkdtempSync(join(tmpdir(), `${id}-`))
assert.ok(resolve(dir).startsWith(join(resolve(tmpdir()), `${id}-`)), 'Unexpected test workspace')
const volume = `${id}-assets`, oldImage = `${id}:old`, newImage = `${id}:new`
const names = [`${id}-old`, `${id}-new`, `${id}-rollback`]
const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
const mount = (path, dest) => `type=bind,src=${path},dst=${dest},readonly`
const archive = `type=volume,src=${volume},dst=/usr/share/nginx/html/assets`
const write = (path, content) => writeFileSync(path, content.replace(/\r\n/g, '\n'))
async function get(port, path) {
  const response = await fetch(`http://127.0.0.1:${port}${path}`)
  return { status: response.status, text: await response.text(), headers: response.headers }
}
async function ready(port) {
  for (let i = 0; i < 50; i++) {
    try { if ((await get(port, '/')).status === 200) return } catch { /* Container still starting. */ }
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  throw new Error('Disposable frontend did not start')
}
function build(version, retain) {
  const context = join(dir, version); mkdirSync(join(context, 'html/assets/fonts'), { recursive: true })
  write(join(context, 'html/index.html'), `<html><script type="module" src="/assets/entry-${version}.js"></script></html>`)
  write(join(context, `html/assets/entry-${version}.js`), `export const open = () => import('./detail-${version}.js');`)
  write(join(context, `html/assets/detail-${version}.js`), `export default '${version} quotation detail';`)
  write(join(context, `html/assets/detail-${version}.css`), `body{color:${version === 'old' ? 'black' : 'blue'}}`)
  write(join(context, 'html/assets/fonts/common-hash.woff2'), 'unchanged font bytes')
  write(join(context, 'nginx.conf'), readFileSync(resolve('nginx.conf'), 'utf8'))
  write(join(context, 'retain-assets.sh'), readFileSync(resolve('deploy/frontend/retain-assets.sh'), 'utf8'))
  write(join(context, 'Dockerfile'), `FROM nginx:1.27-alpine
COPY html /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
${retain ? `COPY html/assets /opt/quotation/assets
COPY retain-assets.sh /opt/quotation/retain-assets.sh
RUN chmod 0755 /opt/quotation/retain-assets.sh && ln -s /opt/quotation/retain-assets.sh /docker-entrypoint.d/25-retain-quotation-assets.sh` : ''}
`)
  docker('build', '-q', '-t', retain ? newImage : oldImage, context)
}
try {
  build('old', false); build('new', true)
  docker('run', '-d', '--name', names[0], '--add-host', 'quotation-backend:127.0.0.1', '-p', '127.0.0.1::80', oldImage)
  const port = docker('port', names[0], '80/tcp').split(':').at(-1)
  await ready(port)
  const oldHtml = (await get(port, '/')).text
  const oldJs = (await get(port, '/assets/detail-old.js')).text
  mkdirSync(join(dir, 'seed'))
  docker('cp', `${names[0]}:/usr/share/nginx/html/assets/.`, join(dir, 'seed'))
  docker('volume', 'create', volume)
  docker('run', '--rm', '--network', 'none', '--entrypoint', '/opt/quotation/retain-assets.sh', '--mount', mount(join(dir, 'seed'), '/seed'), '--mount', archive, newImage, '/seed')
  docker('run', '--rm', '--network', 'none', '--entrypoint', '/opt/quotation/retain-assets.sh', '--mount', archive, newImage)
  docker('stop', names[0])
  docker('run', '-d', '--name', names[1], '--add-host', 'quotation-backend:127.0.0.1', '-p', `127.0.0.1:${port}:80`, '--mount', archive, newImage)
  await ready(port)
  assert.match(oldHtml, /entry-old\.js/)
  assert.match((await get(port, '/')).text, /entry-new\.js/)
  for (const version of ['old', 'new']) {
    for (const file of [`entry-${version}.js`, `detail-${version}.js`, `detail-${version}.css`, 'fonts/common-hash.woff2']) {
      const response = await get(port, `/assets/${file}`)
      assert.equal(response.status, 200, file)
      assert.match(response.headers.get('cache-control'), /immutable/)
      if (file.endsWith('.js')) assert.match(response.headers.get('content-type'), /javascript/)
    }
  }
  assert.equal((await get(port, '/assets/detail-old.js')).text, oldJs)
  assert.equal((await get(port, '/assets/nonexistent.js')).status, 404)
  assert.match((await get(port, '/index.html')).headers.get('cache-control'), /no-store/)
  mkdirSync(join(dir, 'collision')); write(join(dir, 'collision/detail-old.js'), 'changed bytes at same URL')
  assert.throws(() => docker('run', '--rm', '--network', 'none', '--entrypoint', '/opt/quotation/retain-assets.sh', '--mount', mount(join(dir, 'collision'), '/seed'), '--mount', archive, newImage, '/seed'), /Immutable asset collision/)
  assert.equal((await get(port, '/assets/detail-old.js')).text, oldJs)
  docker('stop', names[1])
  docker('run', '-d', '--name', names[2], '--add-host', 'quotation-backend:127.0.0.1', '-p', `127.0.0.1:${port}:80`, '--mount', archive, oldImage)
  await ready(port)
  assert.equal((await get(port, '/')).text, oldHtml)
  assert.equal((await get(port, '/assets/detail-new.js')).status, 200)
  assert.equal((await get(port, '/assets/detail-old.js')).text, oldJs)
  console.log('PASS: legacy bootstrap, upgrade, old lazy JS/CSS, new HTML, nested assets, immutable bytes, collision refusal, 404, cache headers, rollback.')
} finally {
  for (const name of names) { try { docker('rm', '-f', name) } catch { /* Not created. */ } }
  try { docker('volume', 'rm', volume) } catch { /* Not created. */ }
  for (const image of [oldImage, newImage]) { try { docker('image', 'rm', image) } catch { /* Not created. */ } }
  assert.ok(resolve(dir).startsWith(join(resolve(tmpdir()), `${id}-`)), 'Refusing unexpected cleanup path')
  rmSync(dir, { recursive: true, force: true })
}
