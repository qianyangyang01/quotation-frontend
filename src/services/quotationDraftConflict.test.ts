import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { expect, it, vi } from 'vitest'

// Exercise the actual view handlers with controlled network completion and timers.
const source = readFileSync(new URL('../views/QuotationSystemView.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
const ast = ts.createSourceFile('view.ts', source, ts.ScriptTarget.Latest, true)
function handler(name: string, state: Record<string, unknown>) {
  const node = ast.statements.find(n => ts.isFunctionDeclaration(n) && n.name?.text === name)!
  const js = ts.transpile(node.getText(ast), { target: ts.ScriptTarget.ES2022 })
  return new Function('state', `with(state){ ${js}; return ${name} }`)(state)
}
it('keeps editing paused after conflict without scheduling another save or losing dirty input', () => {
  const state = { draftReady: { value: true }, lastSavedDraftSignature: 'old', draftDirty: false,
    draftStatus: { value: 'conflict' }, resolvingDraftConflict: { value: false }, window: { setTimeout: vi.fn() } }
  handler('markDraftDirty', state)('new')
  expect(state.draftDirty).toBe(true)
  expect(state.draftStatus.value).toBe('conflict')
  expect(state.window.setTimeout).not.toHaveBeenCalled()
})
it('rejects a flush during conflict so navigation cannot silently discard changes', async () => {
  await expect(handler('flushDraft', { draftStatus: { value: 'conflict' } })()).rejects.toThrow('当前输入已保留')
})
it('saves edits made during explicit overwrite afterwards using the returned version', async () => {
  const state = { resolvingDraftConflict: { value: false }, window: { clearTimeout: vi.fn() }, draftTimer: 0,
    draftSavePromise: null, draftPayload: () => ({ name: 'before' }), draftSignature: () => '{"name":"after"}',
    loadQuotationDraft: async () => ({ version: 3 }), saveQuotationDraft: vi.fn(async () => ({ version: 4 })),
    draftVersion: { value: 2 }, draftUpdatedAt: { value: '' }, lastSavedDraftSignature: '', draftDirty: false,
    draftStatus: { value: 'conflict' }, showDraftConflictDialog: { value: true }, toast: vi.fn(), flushDraft: vi.fn() }
  await handler('overwriteServerDraftAfterConflict', state)()
  expect(state.saveQuotationDraft).toHaveBeenCalledWith({ name: 'before' }, 3)
  expect(state.draftVersion.value).toBe(4)
  expect(state.draftDirty).toBe(true)
  expect(state.flushDraft).toHaveBeenCalledOnce()
})
