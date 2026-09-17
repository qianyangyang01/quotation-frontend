// @vitest-environment happy-dom
import { readFileSync } from 'node:fs'
import { createApp, computed, ref, h, nextTick } from 'vue'
import ts from 'typescript'
import { expect, it, vi } from 'vitest'
import QuotationPreviewSave from '@/components/quotation/QuotationPreviewSave.vue'
import type { QuotationMatrixRow } from '@/components/quotation/types'
import { loadQuotePhotos } from '@/services/quoteLocalPhotos'

vi.mock('@/services/quoteLocalPhotos', async original => ({ ...await original<typeof import('@/services/quoteLocalPhotos')>(), loadQuotePhotos: vi.fn() }))

it('keeps temporary photos through repricing with the production product reset key, and clears on account changes', async () => {
  const source = readFileSync('src/views/QuotationSystemView.vue', 'utf8')
  const script = source.split('<script setup lang="ts">')[1]!.split('</script>')[0]!
  const ast = ts.createSourceFile('view.ts', script, ts.ScriptTarget.Latest, true)
  const declaration = ast.statements.find(n => ts.isVariableStatement(n) && n.declarationList.declarations.some(d => d.name.getText(ast) === 'quoteSheetResetKey'))!
  const currentAuthUser = ref({ id: 'employee-a' }), quoteMode = ref('bundle'), skus = ref(['ONE', 'TWO'])
  const key = new Function('computed', 'currentAuthUser', 'quoteMode', 'activePurchaseSkus', ts.transpile(declaration.getText(ast)) + ';return quoteSheetResetKey')(computed, currentAuthUser, quoteMode, () => skus.value)
  expect(source.match(/<QuotationPreviewSave[\s\S]*?\/>/)![0]).toContain(':reset-key="quoteSheetResetKey"')
  const revision = ref(1)
  const row: QuotationMatrixRow = { country:'US', carrier:'4PX', channelKey:'a', ruleId:1, rule:'', channelCode:'a', transport:'', eta:'5-8 days', quote1:1, quote2:2, quote3:3, quoteCustom:5, freight:0, totalCostCny:6.7, profitCny:0, quoteCny:6.7, taxIncluded:false, taxConfigured:true, taxRatePercent:null, countryFixedTaxUsd:0, taxFeeMode:'no-tax', taxLabel:'无税费', tax1Usd:0, tax2Usd:0, tax3Usd:0, taxCustomUsd:0 }
  const image = document.createElement('img')
  vi.mocked(loadQuotePhotos).mockResolvedValue([{ url:'blob:photo', name:'fixture.png', image }])
  const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp({ render: () => h(QuotationPreviewSave, { rows:[row], countries:[], salesperson:'QA', contextKey:'price-'+revision.value, resetKey:key.value, skus:skus.value, sourcePending:false, matrixModeLabel:'common', customerName:'QA', productName:'Bundle', sku:'ONE+TWO', customerGrade:'S', coefficient:1, customQuantity:5, unitLabel:'套', exchangeRate:6.7, primaryCountry:'US', primaryCarrier:'4PX', primaryRule:'', primaryCnyPrice:6.7, primaryUsdPrice:1 }) })
  try {
    app.mount(host)
    const input = host.querySelector<HTMLInputElement>('input[type=file]')!
    Object.defineProperty(input,'files',{value:[new File(['x'],'fixture.png',{type:'image/png'})]})
    input.dispatchEvent(new Event('change'))
    for(let i=0;i<8;i++)await nextTick()
    expect(host.querySelector('.sheet-photo-cell')).not.toBeNull()
    revision.value++
    await nextTick(); await nextTick()
    expect(host.querySelector('.sheet-photo-cell')).not.toBeNull()
    currentAuthUser.value={id:'employee-b'}
    await nextTick(); await nextTick()
    expect(host.querySelector('.sheet-photo-cell')).toBeNull()
    expect(revoke).toHaveBeenCalledWith('blob:photo')
  } finally { app.unmount(); host.remove(); revoke.mockRestore() }
})
