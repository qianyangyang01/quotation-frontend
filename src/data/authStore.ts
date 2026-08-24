import { computed, reactive } from 'vue'
import { api, resetCsrf } from '@/services/http'
import { clearPublishedLogisticsCache } from '@/data/publishedLogisticsRepository'

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
  authState.current = { id: session.id, name: session.name, account: session.account, role: session.role, status: 'enabled', mustChangePassword: session.mustChangePassword, passwordUpdatedAt: '' }
  authState.permissions = [...session.permissions]
}

export async function initializeAuth(force = false) {
  if (authState.initialized && !force) return
  if (initialization && !force) return initialization
  initialization = (async () => {
    try { applySession(await api.get<SessionUser>('/auth/me')) }
    catch { authState.current = null; authState.permissions = [] }
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
  try { applySession(await api.post<SessionUser>('/auth/login', { account: account.trim().toUpperCase(), password })); authState.initialized = true; return { ok: true as const, user: currentAuthUser.value } }
  catch (error) { return { ok: false as const, message: error instanceof Error ? error.message : '登录失败' } }
}
export async function logout() { try { await api.post('/auth/logout') } finally { await clearPublishedLogisticsCache(); authState.current = null; authState.permissions = []; resetCsrf() } }
export function hasPermission(permission: PermissionKey) { return isAuthenticated.value && authState.permissions.includes(permission) }
export function defaultHomeForRole(role = currentAuthUser.value.role) { if (role === 'logistics') return '/quotation/logistics'; if (role === 'purchase') return '/quotation/products'; if (role === 'employee') return '/quotation'; return '/quotation/overview' }

export async function loadAuthUsers() { authState.users = await api.get<AuthUser[]>('/users'); return authState.users }
export async function saveAuthUser(input: { name: string; account: string; role: RoleKey; status: AccountStatus; password?: string }) {
  if (!input.password) throw new Error('请设置初始密码')
  const result = await api.post<AuthUser>('/users', input); authState.users.push(result); return result
}
export async function updateAuthUserRole(id: string, role: RoleKey) {
  const current = authState.users.find(user => user.id === id); if (!current) throw new Error('账号不存在')
  const updated = await api.patch<AuthUser>(`/users/${id}`, { role, status: current.status }); Object.assign(current, updated); return updated
}
export async function updateAuthUserStatus(id: string, status: AccountStatus) {
  const current = authState.users.find(user => user.id === id); if (!current) throw new Error('账号不存在')
  if (current.account === currentAuthUser.value.account && status === 'disabled') throw new Error('不能停用当前登录账号')
  const updated = await api.patch<AuthUser>(`/users/${id}`, { role: current.role, status }); Object.assign(current, updated); return updated
}
export async function resetAuthUserPassword(id: string, password: string) { await api.post(`/users/${id}/reset-password`, { password }) }
export async function changeCurrentPassword(currentPassword: string, newPassword: string) { await api.post('/auth/change-password', { currentPassword, newPassword }); if (authState.current) authState.current.mustChangePassword = false }
