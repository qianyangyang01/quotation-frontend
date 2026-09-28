import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { expect, it, vi } from 'vitest'
import { ApiError } from './http'

// Exercise the actual view handlers with controlled network completion and timers.
const source = readFileSync(new URL('../views/QuotationSystemView.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
const ast = ts.createSourceFile('view.ts', source, ts.ScriptTarget.Latest, true)
function handler(name: string, state: Record<string, unknown>) {
  const node = ast.statements.find(n => ts.isFunctionDeclaration(n) && n.name?.text === name)!
  const js = ts.transpile(node.getText(ast), { target: ts.ScriptTarget.ES2022 })
  return new Function('state', `with(state){ ${js}; return ${name} }`)(state)
}
it('never flushes or resolves server draft conflicts for an ordinary quotation', async () => {
  const state = { draftSource: { value: undefined }, draftStatus: { value: 'conflict' },
    saveQuotationDraft: vi.fn(), loadQuotationDraft: vi.fn() }
  await handler('flushDraft', state)()
  await handler('overwriteServerDraftAfterConflict', state)()
  await handler('reloadServerDraftAfterConflict', state)()
  expect(state.saveQuotationDraft).not.toHaveBeenCalled()
  expect(state.loadQuotationDraft).not.toHaveBeenCalled()
})
it('does not restore an ordinary draft when explicitly reloading a completed withdrawal', async () => {
  const state = { draftSource: { value: { id: 'withdrawn' } as { id: string } | undefined },
    resolvingDraftConflict: { value: false }, window: { clearTimeout: vi.fn() }, draftTimer: 0,
    draftSavePromise: null, draftSignature: () => 'unchanged', draftReady: { value: true },
    loadQuotationDraft: async () => ({ exists: true, version: 5, payload: { customerName: 'old ordinary' } }),
    draftVersion: { value: 4 }, draftUpdatedAt: { value: '' }, applyDraftPayload: vi.fn(), resetLocalDraft: vi.fn(),
    establishDraftBaseline: vi.fn(), showDraftConflictDialog: { value: true }, toast: vi.fn() }
  await handler('reloadServerDraftAfterConflict', state)()
  expect(state.applyDraftPayload).not.toHaveBeenCalled()
  expect(state.resetLocalDraft).toHaveBeenCalledOnce()
  expect(state.establishDraftBaseline).toHaveBeenCalledWith('idle')
  expect(state.draftSource.value).toBeUndefined()
  expect(state.draftVersion.value).toBe(-1)
  expect(state.showDraftConflictDialog.value).toBe(false)
})
it.each(['draft', 'quotation', 'timeout'])('distinguishes a %s failure during Save and always releases the busy state', async stage => {
  const error = stage === 'timeout' ? new ApiError('保存结果尚未确认，当前输入已保留', 0, 'QUOTATION_REQUEST_TIMEOUT', 'save-check')
    : new ApiError(stage === 'draft' ? '草稿已在另一个页面更新' : '汇率已变化', 409, 'CONFLICT', 'save-check')
  const state = {
    purchaseTaxBlockReason: { value: '' }, savingQuotation: { value: false }, countryLoads: { value: 0 }, countryLoadError: { value: '' },
    showSaveValidation: { value: false }, saveValidationIssues: { value: [] }, checkLiveVersions: vi.fn(async () => true),
    flushDraft: vi.fn(async () => { if (stage === 'draft') throw error }),
    save: vi.fn(async () => { throw error }), logisticsLoadState: { value: 'ready' }, syncPending: { value: '' }, ApiError, toast: vi.fn(),
  }
  await handler('attemptSave', state)()
  expect(state.syncPending.value).toBe(stage === 'quotation' ? '汇率已变化' : '')
  expect(state.save).toHaveBeenCalledTimes(stage === 'draft' ? 0 : 1)
  expect(state.toast).toHaveBeenCalledWith(`${error.message}（请求编号：save-check）`)
  expect(state.savingQuotation.value).toBe(false)
})
it('keeps editing paused after conflict without scheduling another save or losing dirty input', () => {
  const state = { draftReady: { value: true }, lastSavedDraftSignature: 'old', draftDirty: false,
    draftStatus: { value: 'conflict' }, draftSource:{value:{id:'withdrawn'}},resolvingDraftConflict: { value: false }, window: { setTimeout: vi.fn() } }
  handler('markDraftDirty', state)('new')
  expect(state.draftDirty).toBe(true)
  expect(state.draftStatus.value).toBe('conflict')
  expect(state.window.setTimeout).not.toHaveBeenCalled()
})
it('rejects a flush during conflict so navigation cannot silently discard changes', async () => {
  await expect(handler('flushDraft', { draftSource:{value:{id:'withdrawn'}}, draftStatus: { value: 'conflict' } })()).rejects.toThrow('当前输入已保留')
})
it('saves edits made during explicit overwrite afterwards using the returned version', async () => {
  const state = { draftSource:{value:{id:'withdrawn'}},resolvingDraftConflict: { value: false }, window: { clearTimeout: vi.fn() }, draftTimer: 0,
    draftSavePromise: null, draftPayload: () => ({ name: 'before' }), draftSignature: () => '{"name":"after"}',
    loadQuotationDraft: async () => ({ version: 3, sourceQuote:{id:'withdrawn'} }), saveQuotationDraft: vi.fn(async () => ({ version: 4 })),
    draftVersion: { value: 2 }, draftUpdatedAt: { value: '' }, lastSavedDraftSignature: '', draftDirty: false,
    draftStatus: { value: 'conflict' }, showDraftConflictDialog: { value: true }, toast: vi.fn(), flushDraft: vi.fn() }
  await handler('overwriteServerDraftAfterConflict', state)()
  expect(state.saveQuotationDraft).toHaveBeenCalledWith({ name: 'before' }, 3, 'withdrawn')
  expect(state.draftVersion.value).toBe(4)
  expect(state.draftDirty).toBe(true)
  expect(state.flushDraft).toHaveBeenCalledOnce()
})
