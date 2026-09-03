import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const source = readFileSync(new URL('./QuotationSystemView.vue', import.meta.url), 'utf8')

function functionBody(name: string, nextName: string) {
  const start = source.indexOf(`function ${name}`)
  const end = source.indexOf(`function ${nextName}`, start + 1)
  expect(start).toBeGreaterThanOrEqual(0)
  expect(end).toBeGreaterThan(start)
  return source.slice(start, end)
}

describe('quotation logistics query flow contract', () => {
  it('shows a single product before starting logistics in the background', () => {
    const body = functionBody('queryProduct', 'queryBundleItem')
    expect(body).not.toContain('await ensureQuoteLogistics')
    expect(body.indexOf('toast(`已加载')).toBeLessThan(body.indexOf('startQuoteLogisticsInBackground(p)'))
  })

  it('loads bundle products sequentially and starts logistics only once', () => {
    const body = functionBody('queryBundleItems', 'addBundleItem')
    expect(body).toContain('await queryBundleItem(item, { loadLogistics: false, announce: false })')
    expect(body.match(/startQuoteLogisticsInBackground/g)).toHaveLength(1)
  })

  it('cancels the previous request and blocks saves for unfinished logistics', () => {
    const cancelBody = functionBody('cancelQuoteLogistics', 'startQuoteLogisticsInBackground')
    expect(cancelBody).toContain('logisticsRequest?.abort()')
    expect(cancelBody).toContain('replaceLogisticsRules([])')
    expect(source).toContain("logisticsLoadState.value === 'loading' ? '物流规则正在加载，请稍候'")
    expect(source).toContain("logisticsLoadState.value === 'error' ? logisticsLoadError.value || '物流规则加载失败'")
  })

  it('keeps loaded product data when logistics fails', () => {
    const body = functionBody('ensureQuoteLogistics', 'changeLogisticsAttribute')
    const failure = body.slice(body.indexOf('} catch (error)'))
    expect(failure).not.toContain('applyPurchaseRecord')
    expect(failure).not.toContain("p.sku = ''")
    expect(failure).toContain("p.status = '物流规则加载失败'")
  })
})
