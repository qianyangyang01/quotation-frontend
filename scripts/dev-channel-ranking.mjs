import { createServer, mergeConfig } from 'vite'
import config from '../vite.config.ts'
// Loopback-only acceptance against a SELECT-only local historical snapshot server.
const server = await createServer(mergeConfig(config, {
  configFile: false,
  plugins: [{
    name: 'local-ranking-snapshot-context',
    enforce: 'pre',
    transform(code, id) {
      if (id.replaceAll('\\', '/').endsWith('/src/views/QuotationOverviewView.vue')) {
        return code.replace('const initialDates = overviewDateRange()', "const initialDates = {startDate:'2026-09-01',endDate:'2026-09-23'}")
      }
    },
    transformIndexHtml(html) {
      return html.replace('<body>', '<body><div style="padding:10px 24px;background:#233b4c;color:#f5d09b;font:13px Microsoft YaHei">本地只读验收 · 真实历史备份：2026-09-23 · 非当前线上数据 · 未上线</div>')
    },
  }],
  server: { host: '127.0.0.1', port: 5198, strictPort: true, proxy: { '/api': { target: 'http://127.0.0.1:18119', changeOrigin: false } } },
}))
await server.listen()
server.printUrls()
