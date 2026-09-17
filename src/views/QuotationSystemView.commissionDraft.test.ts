import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { afterEach, expect, it, vi } from 'vitest'

const source = readFileSync(new URL('./QuotationSystemView.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
const parsed = ts.createSourceFile('view.ts', source, ts.ScriptTarget.Latest, true)
const fn = parsed.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'markDraftDirty')!
const code = ts.transpileModule(fn.getText(parsed), {compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText

afterEach(() => vi.useRealTimers())
it('automatically retries when an invalid threshold is corrected back to the saved value', () => {
  vi.useFakeTimers()
  const status = {value:'error'}, error = {value:'佣金阈值必须大于0且不超过1'}, flush = vi.fn().mockResolvedValue(undefined)
  const context = {draftReady:{value:true},lastSavedDraftSignature:'saved-0.95',draftDirty:true,draftStatus:status,draftError:error,resolvingDraftConflict:{value:false},draftTimer:0,window:{clearTimeout,setTimeout},flushDraft:flush}
  const mark = new Function(...Object.keys(context), code + '\nreturn markDraftDirty')(...Object.values(context))
  mark('saved-0.95')
  expect(status.value).toBe('dirty')
  expect(error.value).toBe('')
  vi.advanceTimersByTime(800)
  expect(flush).toHaveBeenCalledOnce()
})
