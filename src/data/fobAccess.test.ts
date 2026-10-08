import { afterEach, expect, it } from 'vitest'
import { authState, roleDefinitions } from './authStore'
import { canMaintainFob, canUseFob } from './fobAccess'

afterEach(() => { authState.current = null; authState.permissions = [] })
it.each(roleDefinitions)('keeps quotation and FOB maintenance access separate for $name', role => {
  authState.current = { id:role.key, account:role.key, name:role.name, role:role.key, status:'enabled', mustChangePassword:false, passwordUpdatedAt:'' }
  authState.permissions = [...role.permissions]
  expect(canUseFob.value).toBe(role.permissions.includes('quote'))
  expect(canMaintainFob.value).toBe(role.key === 'super_admin')
  authState.current.status = 'disabled'
  expect(canUseFob.value).toBe(false); expect(canMaintainFob.value).toBe(false)
})
it('does not retain FOB access after logout even if a previous permission list remains', () => {
  authState.current = null; authState.permissions = ['quote']
  expect(canUseFob.value).toBe(false); expect(canMaintainFob.value).toBe(false)
})
