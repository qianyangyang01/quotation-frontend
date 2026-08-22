import { describe, expect, it } from 'vitest'
import { defaultHomeForRole, roleDefinitions, validatePassword } from './authStore'

describe('quotation authorization model', () => {
  it('keeps five isolated quotation roles with employee least privilege', () => {
    expect(roleDefinitions.map(role => role.key)).toEqual(['super_admin', 'finance', 'logistics', 'purchase', 'employee'])
    expect(roleDefinitions.find(role => role.key === 'employee')?.permissions).toEqual(['quote', 'myRecords'])
  })

  it('enforces password baseline and role landing pages', () => {
    expect(validatePassword('short')).toBeTruthy()
    expect(validatePassword('longpassword')).toBeTruthy()
    expect(validatePassword('SecurePass123')).toBe('')
    expect(defaultHomeForRole('logistics')).toBe('/quotation/logistics')
    expect(defaultHomeForRole('employee')).toBe('/quotation')
  })
})
