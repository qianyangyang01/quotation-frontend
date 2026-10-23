// @vitest-environment happy-dom
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { afterEach, expect, it, vi } from 'vitest'

const source = readFileSync('src/views/QuotationSystemView.vue', 'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
const ast = ts.createSourceFile('view.ts', source, ts.ScriptTarget.Latest, true)
const declaration = ast.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'copyQuoteRows')!
const js = ts.transpileModule(declaration.getText(ast), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText
const rows = [{ country: 'US', carrier: '4PX', transport: 'route', eta: '7 days', quote1: 10, quote2: 20, quote3: 30 }]
function setup() {
  const version = { updateRequired: false }
  const toast = vi.fn(), writeText = vi.fn().mockResolvedValue(undefined)
  const appUpdate = { assertCurrent: () => { if (version.updateRequired) throw new Error('系统已更新，请刷新后重新报价') } }
  vi.stubGlobal('navigator', { clipboard: { writeText } })
  const copy = new Function('appUpdate', 'toast', 'purchaseTaxBlockReason', 'specialPackagingError', 'commissionError', 'quoteMode', 'quoteCnyFromUsd', 'exchange', js + '; return copyQuoteRows')(
    appUpdate, toast, { value: '' }, { value: '' }, { value: '' }, { value: 'single' }, (usd: number, rate: number) => usd * rate, { value: { usd: 6.7 } },
  ) as (rows: unknown[]) => Promise<void>
  return { version, toast, writeText, copy }
}
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); document.body.innerHTML = '' })

it('blocks the matrix clipboard entry point after an update', async () => {
  const { version, toast, writeText, copy } = setup()
  version.updateRequired = true
  await copy(rows)
  expect(writeText).not.toHaveBeenCalled()
  expect(toast).toHaveBeenCalledWith('系统已更新，请刷新后重新报价')
})

it('keeps a current matrix clipboard invocation synchronous and exports its existing values', async () => {
  const { writeText, copy } = setup()
  const pending = copy(rows)
  expect(writeText).toHaveBeenCalledOnce()
  expect(writeText.mock.lastCall![0]).toContain('10.00\t67.00')
  await pending
})

it('does not use a delayed legacy clipboard fallback after an update', async () => {
  const { version, toast, writeText, copy } = setup()
  let fail!: (error: Error) => void
  writeText.mockImplementationOnce(() => new Promise((_resolve, reject) => { fail = reject }))
  const createElement = vi.spyOn(document, 'createElement')
  const pending = copy(rows)
  version.updateRequired = true; fail(new Error('Clipboard permission changed'))
  await pending
  expect(createElement).not.toHaveBeenCalledWith('textarea')
  expect(toast).toHaveBeenCalledWith('系统已更新，请刷新后重新报价')
})

it('marks only live quotation callers explicitly, including batch record-mode editors', () => {
  const component = (name: string) => readFileSync(`src/components/quotation/${name}.vue`, 'utf8')
  expect(component('QuotationPreviewSave')).toContain('<CustomerQuoteSheet current-quote')
  expect(component('BatchSingleSheetEditor')).toContain('<CustomerQuoteSheet ref="editor" current-quote')
  expect(component('FobQuotePanel')).toContain('<FobQuoteSheet current-quote')
  expect(component('FobBatchQuoteDialog').match(/:current-quote="!savedNo"/g)).toHaveLength(2)
  expect(readFileSync('src/views/BatchSingleQuotationView.vue', 'utf8')).toContain(':current-quote="!saved"')
  for (const name of ['QuotationRecordCopyActions', 'QuotationBatchCopy', 'BatchSingleRecordDetail', 'FobRecordDetail']) expect(component(name)).not.toContain('current-quote')
})
