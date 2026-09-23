import { computed, reactive } from 'vue'
import { api, request, resetCsrf, setRequestAccount } from '@/services/http'
import { clearPublishedLogisticsCache } from '@/data/publishedLogisticsRepository'
import { clearFinanceSettingsCache } from '@/services/financeSettings'

export type RoleKey = 'super_admin' | 'finance' | 'logistics' | 'purchase' | 'employee'
export type PermissionKey = 'quote' | 'purchase' | 'logistics' | 'finance' | 'myRecords' | 'allRecords' | 'permissions'
export type AccountStatus = 'enabled' | 'disabled'

export interface AuthUser {
  id: string; name: string; account: string; role: RoleKey; status: AccountStatus
  mustChangePassword: boolean; passwordUpdatedAt: string; version?: number
}
export interface RoleDefinition { key: RoleKey; name: string; shortName: string; description: string; permissions: PermissionKey[] }

export const roleDefinitions: RoleDefinition[] = [
  { key: 'super_admin', name: '超级管理员', shortName: '超管', description: '查看并维护全部业务模块、整体报价记录和账号权限。', permissions: ['quote', 'purchase', 'logistics', 'finance', 'allRecords', 'permissions'] },
  { key: 'finance', name: '财务', shortName: '财务', description: '现阶段与超级管理员使用相同业务权限。', permissions: ['quote', 'purchase', 'logistics', 'finance', 'allRecords', 'permissions'] },
  { key: 'logistics', name: '物流', shortName: '物流', description: '仅维护物流商、渠道、价格版本、审核与发布。', permissions: ['logistics'] },
  { key: 'purchase', name: '采购', shortName: '采购', description: '仅维护采购商品资料及 Excel 导入数据。', permissions: ['purchase'] },
  { key: 'employee', name: '员工', shortName: '员工', description: '仅发起本人报价并查看本人的报价记录。', permissions: ['quote', 'myRecords'] },
]

type SessionUser = { id: string; account: string; name: string; role: RoleKey; permissions: PermissionKey[]; mustChangePassword: boolean }
const anonymous: AuthUser = { id: '', name: '未登录', account: '', role: 'employee', status: 'disabled', mustChangePassword: false, passwordUpdatedAt: '' }
export const authState = reactive<{ users: AuthUser[]; current: AuthUser | null; permissions: PermissionKey[]; initialized: boolean }>({ users: [], current: null, permissions: [], initialized: false })
let initialization: Promise<void> | null = null

function applySession(session: SessionUser) {
  setRequestAccount(session.account)
  authState.current = { id: session.id, name: session.name, account: session.account, role: session.role, status: 'enabled', mustChangePassword: session.mustChangePassword, passwordUpdatedAt: '' }
  authState.permissions = [...session.permissions]
}

export async function initializeAuth(force = false) {
  if (authState.initialized && !force) return
  if (initialization && !force) return initialization
  initialization = (async () => {
    try { applySession(await api.get<SessionUser>('/auth/me')) }
    catch { setRequestAccount(''); authState.current = null; authState.permissions = [] }
    finally { authState.initialized = true; initialization = null }
  })()
  return initialization
}

export const isAuthenticated = computed(() => authState.current?.status === 'enabled')
export const currentAuthUser = computed(() => authState.current || anonymous)
export const currentRole = computed(() => roleDefinitions.find(role => role.key === currentAuthUser.value.role) || roleDefinitions[4])

export function validatePassword(password: string) {
  if (password.length < 10) return '密码至少需要10位'
  if (!/[A-Za-z]/.test(password) || !/\d/.test(password)) return '密码必须同时包含字母和数字'
  return ''
}

export async function login(account: string, password: string) {
  try {
    const session = await api.post<SessionUser>('/auth/login', { account: account.trim().toUpperCase(), password })
    // Invalidate prior settings even when logging in again without a logout.
    // The destination workspace must verify fresh settings before calculating.
    clearFinanceSettingsCache()
    applySession(session)
    authState.initialized = true
    return { ok: true as const, user: currentAuthUser.value }
  }
  catch (error) { return { ok: false as const, message: error instanceof Error ? error.message : '登录失败' } }
}
export async function logout() { try { await api.post('/auth/logout') } finally { clearFinanceSettingsCache(); await clearPublishedLogisticsCache(); authState.current = null; authState.permissions = []; setRequestAccount(''); resetCsrf() } }
export function hasPermission(permission: PermissionKey) { return isAuthenticated.value && authState.permissions.includes(permission) }
export function hasAnyPermission(...permissions: PermissionKey[]) { return permissions.some(permission => hasPermission(permission)) }
export function canAccessMyRecords(permissions: readonly PermissionKey[]) { return permissions.includes('myRecords') || permissions.includes('allRecords') }
export function defaultHomeForRole(role = currentAuthUser.value.role) { if (role === 'logistics') return '/quotation/logistics'; if (role === 'purchase') return '/quotation/products'; if (role === 'employee') return '/quotation'; return '/quotation/overview' }

export async function loadAuthUsers() {
  const loaded = await api.get<AuthUser[]>('/users', { signal: AbortSignal.timeout(20_000) })
  authState.users = loaded.map(user => {
    const known = authState.users.find(item => item.id === user.id)
    return known && (known.version ?? -1) > (user.version ?? -1) ? known : user
  })
  return authState.users
}
export async function saveAuthUser(input: { name: string; account: string; role: RoleKey; status: AccountStatus; password?: string }) {
  if (!input.password) throw new Error('请设置初始密码')
  const result = await request<AuthUser>('/users', { method: 'POST', body: JSON.stringify(input), signal: AbortSignal.timeout(20_000) }); authState.users.push(result); return result
}
export async function updateAuthUserRole(id: string, role: RoleKey) {
  const current = authState.users.find(user => user.id === id); if (!current) throw new Error('账号不存在')
  const updated = await api.patch<AuthUser>(`/users/${id}`, { role, status: current.status, version: userVersion(current) }); applyUserUpdate(updated); return updated
}
export async function updateAuthUserStatus(id: string, status: AccountStatus) {
  const current = authState.users.find(user => user.id === id); if (!current) throw new Error('账号不存在')
  if (current.account === currentAuthUser.value.account && status === 'disabled') throw new Error('不能停用当前登录账号')
  const updated = await api.patch<AuthUser>(`/users/${id}`, { role: current.role, status, version: userVersion(current) }); applyUserUpdate(updated); return updated
}
function userVersion(user: AuthUser) {
  if (!Number.isSafeInteger(user.version) || user.version! < 0) throw new Error('账号资料缺少版本，请重新加载账号列表后重试')
  return user.version!
}
function applyUserUpdate(updated: AuthUser) {
  const current = authState.users.find(user => user.id === updated.id)
  if (current && (updated.version ?? -1) >= (current.version ?? -1)) Object.assign(current, updated)
}
export async function resetAuthUserPassword(id: string, password: string) { await api.post(`/users/${id}/reset-password`, { password }) }
export async function changeCurrentPassword(currentPassword: string, newPassword: string) { await api.post('/auth/change-password', { currentPassword, newPassword }); if (authState.current) authState.current.mustChangePassword = false }
