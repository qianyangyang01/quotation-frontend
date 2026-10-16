import { describe, expect, it } from 'vitest'
import { canAccessMyRecords, defaultHomeForRole, roleDefinitions, validatePassword } from './authStore'

describe('quotation authorization model', () => {
  it('preserves business employee permissions and adds operations without business access', () => {
    expect(roleDefinitions.map(role => role.key)).toEqual(['super_admin', 'finance', 'logistics', 'purchase', 'employee', 'operations_employee'])
    expect(roleDefinitions.find(role => role.key === 'employee')).toMatchObject({ name: '业务-员工', permissions: ['quote', 'myRecords'] })
    expect(roleDefinitions.find(role => role.key === 'operations_employee')).toMatchObject({ name: '运营-员工', permissions: [] })
  })

  it('enforces password baseline and role landing pages', () => {
    expect(validatePassword('short')).toBeTruthy()
    expect(validatePassword('longpassword')).toBeTruthy()
    expect(validatePassword('SecurePass123')).toBe('')
    expect(defaultHomeForRole('logistics')).toBe('/quotation/logistics')
    expect(defaultHomeForRole('employee')).toBe('/quotation')
    expect(defaultHomeForRole('operations_employee')).toBe('/quotation/no-access')
  })

  it('allows either personal-record or all-record permission into my records', () => {
    expect(canAccessMyRecords(['myRecords'])).toBe(true)
    expect(canAccessMyRecords(['allRecords'])).toBe(true)
    expect(canAccessMyRecords(['quote'])).toBe(false)
    expect(canAccessMyRecords(roleDefinitions.find(role => role.key === 'super_admin')!.permissions)).toBe(true)
    expect(canAccessMyRecords(roleDefinitions.find(role => role.key === 'finance')!.permissions)).toBe(true)
    expect(canAccessMyRecords(roleDefinitions.find(role => role.key === 'employee')!.permissions)).toBe(true)
    expect(canAccessMyRecords(roleDefinitions.find(role => role.key === 'operations_employee')!.permissions)).toBe(false)
  })
})
