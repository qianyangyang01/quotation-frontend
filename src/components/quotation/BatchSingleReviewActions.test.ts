// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import Actions from './BatchSingleReviewActions.vue'
import { reviewBatchSingle, type BatchSingleDocument } from '@/services/batchSingleQuotation'
import { authState } from '@/data/authStore'
import { setPricingTestPermissions, clearPricingTestPermissions } from '@/test/pricingPermissions'
vi.mock('@/services/batchSingleQuotation', async original => ({ ...await original<typeof import('@/services/batchSingleQuotation')>(), reviewBatchSingle: vi.fn() }))
let app: App
const button = (text: string) => [...document.querySelectorAll('button')].find(b => b.textContent?.includes(text))!
const fixture = () => ({ id:'batch', _reviewVersion:3, items:[{input:{primarySku:'A'}},{input:{primarySku:'B'}}], review:{status:'reviewing',claimantAccount:'PRIVACY_TEST',items:[{index:0,status:'pending'},{index:1,status:'pending'}]} }) as BatchSingleDocument
beforeEach(() => { setPricingTestPermissions(['allRecords']); authState.current!.role='finance'; vi.clearAllMocks() })
afterEach(() => { app?.unmount(); document.body.innerHTML=''; clearPricingTestPermissions() })
async function mount() { const host=document.createElement('div');document.body.append(host);const saved=vi.fn();app=createApp(Actions,{document:fixture(),selected:[1],hiddenSelected:1,onSaved:saved});app.mount(host);await nextTick();return saved }
it('keeps four outcomes and requires a category and accepts empty reasons for selected returns', async () => {
 const saved=await mount();expect(document.querySelectorAll('input[type=radio]')).toHaveLength(4);expect(document.body.textContent).toContain('其中 1 项不在本页');button('退回所选').click();await nextTick();button('确认退回').click();await nextTick();expect(reviewBatchSingle).not.toHaveBeenCalled();expect(document.body.textContent).toContain('请为每个退回SKU选择异常类型');
 const reason=document.querySelector<HTMLTextAreaElement>('[aria-label="第2项退回原因"]')!;reason.value='';reason.dispatchEvent(new Event('input'));const type=document.querySelector<HTMLSelectElement>('[aria-label="第2项异常类型"]')!;expect(type.value).toBe('');type.value='other';type.dispatchEvent(new Event('change'));await nextTick();vi.mocked(reviewBatchSingle).mockResolvedValue(fixture());button('确认退回').click();await vi.waitFor(()=>expect(saved).toHaveBeenCalledOnce());expect(reviewBatchSingle).toHaveBeenCalledWith(expect.anything(),'complete',expect.any(String),{result:'rejected',note:'',items:[{index:1,reason:'',exceptionType:'other'}]})
})
it('retries the same operation idempotently after a network failure',async()=>{
 await mount();vi.mocked(reviewBatchSingle).mockRejectedValueOnce(new Error('连接中断')).mockResolvedValueOnce(fixture());button('确认审核').click();await vi.waitFor(()=>expect(document.body.textContent).toContain('连接中断'));button('确认审核').click();await vi.waitFor(()=>expect(reviewBatchSingle).toHaveBeenCalledTimes(2));expect(vi.mocked(reviewBatchSingle).mock.calls[0]![2]).toBe(vi.mocked(reviewBatchSingle).mock.calls[1]![2]);expect(vi.mocked(reviewBatchSingle).mock.calls[0]![3]?.items).toEqual([{index:1,reason:''}])
})
it('does not render mutation controls for staff',async()=>{ authState.current!.role='employee';await mount();expect(button('确认审核')).toBeUndefined() })

it('applies only the manual common reason and preserves it across category changes', async () => {
 const saved = await mount()
 button('退回所选').click(); await nextTick()
 const type = document.querySelector<HTMLSelectElement>('[aria-label="第2项异常类型"]')!
 const reason = document.querySelector<HTMLTextAreaElement>('[aria-label="第2项退回原因"]')!
 type.value = 'purchase-price'; type.dispatchEvent(new Event('change')); await nextTick()
 expect(reason.value).toBe('')
 const common = document.querySelector<HTMLTextAreaElement>('[aria-label="统一退回原因"]')!
 common.value = '请核对采购票点'; common.dispatchEvent(new Event('input')); await nextTick()
 button('应用到所选SKU').click(); await nextTick()
 expect(reason.value).toBe('请核对采购票点')
 type.value = 'channel-price'; type.dispatchEvent(new Event('change')); await nextTick()
 expect(reason.value).toBe('请核对采购票点')
 vi.mocked(reviewBatchSingle).mockResolvedValue(fixture())
 button('确认退回').click(); await vi.waitFor(() => expect(saved).toHaveBeenCalledOnce())
 expect(vi.mocked(reviewBatchSingle).mock.calls[0]![3]?.items).toEqual([{ index: 1, reason: '请核对采购票点', exceptionType: 'channel-price' }])
})
