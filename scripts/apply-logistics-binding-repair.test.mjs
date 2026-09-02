import { describe, expect, it } from 'vitest'
import { buildRepairSql } from './apply-logistics-binding-repair.mjs'

describe('logistics binding repair transaction', () => {
  it('locks and compares the exact version and payload before one audited update', () => {
    const sql = buildRepairSql([{ id: 'before' }], [{ id: 'after' }], { financeVersion: 4 }, { id: '11111111-1111-1111-1111-111111111111', requestId: 'repair-1', actor: 'QIANYANGYANG', detail: { count: 48 } })

    expect(sql).toContain('lock table finance_setting')
    expect(sql).toContain("version=4 and payload=$before_policies$")
    expect(sql).toContain('if changed<>1 then raise exception')
    expect(sql).toContain("'logistics.binding.repair'")
    expect(sql).toContain('commit;')
  })

  it('rejects JSON that could terminate its SQL delimiter', () => {
    expect(() => buildRepairSql([{ value: '$before_policies$' }], [], { financeVersion: 4 }, { id: '11111111-1111-1111-1111-111111111111', requestId: 'repair-1', actor: 'QA', detail: {} })).toThrow('reserved SQL delimiter')
  })
})
