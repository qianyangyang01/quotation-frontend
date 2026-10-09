import { authState, type PermissionKey } from '@/data/authStore'

export function setPricingTestPermissions(permissions: PermissionKey[] = ['quote', 'purchase', 'logistics', 'finance', 'allRecords']) {
  authState.current = { id: 'privacy-test', account: 'PRIVACY_TEST', name: '权限测试', role: 'employee', status: 'enabled', mustChangePassword: false, passwordUpdatedAt: '' }
  authState.permissions = [...permissions]
}

export function clearPricingTestPermissions() {
  authState.current = null
  authState.permissions = []
}
