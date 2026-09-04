import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const app = readFileSync(new URL('./App.vue', import.meta.url), 'utf8')
const protectedViews = [
  './views/JerryModuleView.vue',
  './views/LogisticsWorkspaceView.vue',
  './views/PermissionManagementView.vue',
  './views/QuotationOverviewView.vue',
  './views/QuotationRecordsView.vue',
  './views/QuotationSystemView.vue',
  './views/SumaoLogisticsReplicaView.vue',
]

describe('authenticated application shell', () => {
  it('owns one persistent top navigation outside the routed page', () => {
    expect(app).toContain("import AppTopbar from '@/components/AppTopbar.vue'")
    expect(app).toContain('<AppTopbar v-if="showTopbar" />')
    expect(app.indexOf('<AppTopbar')).toBeLessThan(app.indexOf('<RouterView'))
  })

  it('does not recreate the top navigation inside protected route views', () => {
    for (const path of protectedViews) {
      const source = readFileSync(new URL(path, import.meta.url), 'utf8')
      expect(source).not.toContain("import AppTopbar from '@/components/AppTopbar.vue'")
      expect(source).not.toContain('<AppTopbar')
    }
  })
})
