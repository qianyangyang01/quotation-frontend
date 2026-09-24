import { it, expect, vi } from 'vitest'
vi.mock('@/services/financeSettings',()=>({readFinanceSetting:vi.fn(),writeFinanceSetting:vi.fn()}))
import {saveFinanceTaxSettings} from './financeTaxSettings'
import {writeFinanceSetting} from '@/services/financeSettings'
it.each([-1,NaN,Infinity,'',null,'abc'])('rejects invalid duty %s without publishing',async value=>{
 vi.mocked(writeFinanceSetting).mockClear()
 await expect(saveFinanceTaxSettings({countries:[{country:'美国',fixedFeeUsd:value as number,selected:true,enabled:true,sortOrder:1}],providers:[],updatedAt:''})).rejects.toThrow('有效非负')
 expect(writeFinanceSetting).not.toHaveBeenCalled()
})
