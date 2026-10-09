import { describe, it, expect } from 'vitest'
import { normalizeFinanceTaxSettings, calculateFinanceQuoteTax } from './financeTaxSettings'
describe('country provider taxes', () => {
  it('isolates country overrides, keeps legacy behavior, and blocks empty configuration', () => {
    const provider = { provider: 'P', selected: true, mode: 'exempt' as const, channels: [] }
    const country = (name: string) => ({ country: name, selected: true, enabled: true, fixedFeeUsd: 1.5, sortOrder: 1 })
    const settings = normalizeFinanceTaxSettings({ providers: [provider], countries: [country('美国'), { ...country('新西兰'), providers: [{ ...provider, mode: 'taxable' }] }, { ...country('英国'), providers: [] }] })
    expect(calculateFinanceQuoteTax(settings, '美国', 'P', 10).taxUsd).toBe(0)
    expect(calculateFinanceQuoteTax(settings, '新西兰', 'P', 10).taxUsd).toBe(1.5)
    expect(calculateFinanceQuoteTax(settings, '英国', 'P', 10).configured).toBe(false)
    const reload = normalizeFinanceTaxSettings(JSON.parse(JSON.stringify(settings)))
    expect(calculateFinanceQuoteTax(reload, '新西兰', 'P', 10).totalUsd).toBe(11.5)
    reload.countries.find(row => row.country === '新西兰')!.providers![0]!.mode = 'exempt'
    expect(calculateFinanceQuoteTax(settings, '新西兰', 'P', 10).taxUsd).toBe(1.5)
  })
})

it('applies one duty to all quantities and independent surcharge combinations', async () => {
 const { calculateFinanceQuoteFees } = await import('./financeSurchargeSettings')
 for (const taxExempt of [true,false]) for (const feeExempt of [true,false]) {
  const setting=(amount:number,exempt:boolean)=>({countries:[{country:'美国',selected:true,enabled:true,fixedFeeUsd:amount,sortOrder:1,providers:[{provider:'P',selected:true,mode:exempt?'exempt' as const:'taxable' as const,channels:[]}]}],providers:[],updatedAt:''})
  for (const quantity of [1,2,3,10]) for(const key of ['1::P::A','2::P::B']) {
   const r=calculateFinanceQuoteFees(setting(0.3,taxExempt),setting(1.5,feeExempt),'美国','P',10*quantity,key)
   expect(r.taxUsd).toBe(taxExempt?0:0.3)
   expect(r.surchargeUsd).toBe(feeExempt?0:1.5)
   expect(r.totalUsd).toBe(Number((10*quantity+(taxExempt?0:0.3)+(feeExempt?0:1.5)).toFixed(2)))
  }
 }
})
