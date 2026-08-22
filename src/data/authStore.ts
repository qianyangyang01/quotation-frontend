import { computed, reactive } from 'vue'

export type RoleKey = 'super_admin' | 'finance' | 'logistics' | 'purchase' | 'employee'
export type PermissionKey = 'quote' | 'purchase' | 'logistics' | 'finance' | 'myRecords' | 'allRecords' | 'permissions'
export type AccountStatus = 'enabled' | 'disabled'

export interface AuthUser {
  id: string
  name: string
  account: string
  role: RoleKey
  status: AccountStatus
  passwordHash: string
  passwordUpdatedAt: string
}

export interface RoleDefinition {
  key: RoleKey
  name: string
  shortName: string
  description: string
  permissions: PermissionKey[]
}

export const roleDefinitions: RoleDefinition[] = [
  { key: 'super_admin', name: '超级管理员', shortName: '超管', description: '查看并维护全部业务模块、整体报价记录和账号权限。', permissions: ['quote', 'purchase', 'logistics', 'finance', 'allRecords', 'permissions'] },
  { key: 'finance', name: '财务', shortName: '财务', description: '现阶段与超级管理员使用相同业务权限。', permissions: ['quote', 'purchase', 'logistics', 'finance', 'allRecords', 'permissions'] },
  { key: 'logistics', name: '物流', shortName: '物流', description: '仅维护物流商、渠道、价格版本、审核与发布。', permissions: ['logistics'] },
  { key: 'purchase', name: '采购', shortName: '采购', description: '仅维护采购商品资料及 Excel 导入数据。', permissions: ['purchase'] },
  { key: 'employee', name: '员工', shortName: '员工', description: '仅发起本人报价并查看本人的报价记录。', permissions: ['quote', 'myRecords'] },
]

const USERS_KEY = 'milano.auth.users.v1'
const SESSION_ACCOUNT_KEY = 'milano.auth.session-account.v1'
export const DEFAULT_LOCAL_PASSWORD = 'Milano@123'

// 当前项目没有后端，密码只在浏览器本地以不可逆摘要形式保存；正式上线需替换为服务器端认证。
export function hashLocalPassword(account: string, password: string) {
  const source = `${account.trim().toUpperCase()}::${password}`
  let hash = 2166136261
  for (let index = 0; index < source.length; index += 1) {
    hash ^= source.charCodeAt(index)
    hash = Math.imul(hash, 16777619)
  }
  return (hash >>> 0).toString(16).padStart(8, '0')
}

function createDefaultUser(id: string, name: string, account: string, role: RoleKey): AuthUser {
  return { id, name, account, role, status: 'enabled', passwordHash: hashLocalPassword(account, DEFAULT_LOCAL_PASSWORD), passwordUpdatedAt: '2026-08-22T00:00:00.000Z' }
}

const defaultUsers: AuthUser[] = [
  createDefaultUser('user-qyy001', '钱洋洋', 'QYY001', 'super_admin'),
  createDefaultUser('user-cw001', '财务专员', 'CW001', 'finance'),
  createDefaultUser('user-wl001', '物流专员', 'WL001', 'logistics'),
  createDefaultUser('user-cg001', '采购专员', 'CG001', 'purchase'),
  createDefaultUser('user-yw001', '业务员', 'YW001', 'employee'),
]

function validRole(value: unknown): value is RoleKey {
  return roleDefinitions.some(role => role.key === value)
}

function loadUsers(): AuthUser[] {
  if (typeof window === 'undefined') return structuredClone(defaultUsers)
  try {
    const stored = JSON.parse(window.localStorage.getItem(USERS_KEY) || '[]') as Partial<AuthUser>[]
    const valid = stored.filter(item => item.name && item.account && validRole(item.role)).map((item, index) => {
      const account = String(item.account).toUpperCase()
      return {
        id: item.id || `user-${Date.now()}-${index}`,
        name: String(item.name),
        account,
        role: item.role as RoleKey,
        status: item.status === 'disabled' ? 'disabled' as const : 'enabled' as const,
        passwordHash: item.passwordHash || hashLocalPassword(account, DEFAULT_LOCAL_PASSWORD),
        passwordUpdatedAt: item.passwordUpdatedAt || new Date().toISOString(),
      }
    })
    if (valid.length) return valid
  } catch {
    // Malformed local role data falls back to the built-in accounts.
  }
  return structuredClone(defaultUsers)
}

const initialUsers = loadUsers()
const storedSessionAccount = typeof window === 'undefined' ? '' : window.sessionStorage.getItem(SESSION_ACCOUNT_KEY) || ''
const initialAccount = initialUsers.some(user => user.account === storedSessionAccount && user.status === 'enabled') ? storedSessionAccount : ''

export const authState = reactive({ users: initialUsers, currentAccount: initialAccount })
export const isAuthenticated = computed(() => Boolean(authState.currentAccount && authState.users.some(user => user.account === authState.currentAccount && user.status === 'enabled')))
export const currentAuthUser = computed(() => authState.users.find(user => user.account === authState.currentAccount && user.status === 'enabled') || defaultUsers[0])
export const currentRole = computed(() => roleDefinitions.find(role => role.key === currentAuthUser.value.role) || roleDefinitions[0])

function persistUsers() {
  if (typeof window !== 'undefined') window.localStorage.setItem(USERS_KEY, JSON.stringify(authState.users))
}

function persistSession() {
  if (typeof window === 'undefined') return
  if (authState.currentAccount) window.sessionStorage.setItem(SESSION_ACCOUNT_KEY, authState.currentAccount)
  else window.sessionStorage.removeItem(SESSION_ACCOUNT_KEY)
}

export function validatePassword(password: string) {
  if (password.length < 8) return '密码至少需要8位'
  if (!/[A-Za-z]/.test(password) || !/\d/.test(password)) return '密码必须同时包含字母和数字'
  return ''
}

export function login(accountInput: string, password: string) {
  const account = accountInput.trim().toUpperCase()
  const user = authState.users.find(item => item.account === account)
  if (!user || user.passwordHash !== hashLocalPassword(account, password)) return { ok: false as const, message: '账号或密码错误' }
  if (user.status !== 'enabled') return { ok: false as const, message: '该账号已停用，请联系管理员' }
  authState.currentAccount = user.account
  persistSession()
  return { ok: true as const, user }
}

export function logout() {
  authState.currentAccount = ''
  persistSession()
}

export function hasPermission(permission: PermissionKey) {
  return isAuthenticated.value && currentRole.value.permissions.includes(permission)
}

export function defaultHomeForRole(role = currentAuthUser.value.role) {
  if (role === 'logistics') return '/quotation/logistics'
  if (role === 'purchase') return '/quotation/products'
  if (role === 'employee') return '/quotation'
  return '/quotation/overview'
}

export function saveAuthUser(input: { id?: string; name: string; account: string; role: RoleKey; status: AccountStatus; password?: string }) {
  const account = input.account.trim().toUpperCase()
  const name = input.name.trim()
  if (!account || !name) throw new Error('姓名和账号不能为空')
  if (!/^[A-Z0-9_-]{3,24}$/.test(account)) throw new Error('账号只能使用3～24位字母、数字、下划线或短横线')
  const duplicate = authState.users.find(user => user.account === account && user.id !== input.id)
  if (duplicate) throw new Error('账号已存在')
  const existing = input.id ? authState.users.find(user => user.id === input.id) : undefined
  if (!existing && !input.password) throw new Error('请设置初始密码')
  if (input.password) {
    const passwordError = validatePassword(input.password)
    if (passwordError) throw new Error(passwordError)
  }
  const previousAccount = existing?.account
  if (existing) {
    Object.assign(existing, { name, account, role: input.role, status: input.status })
    if (input.password) {
      existing.passwordHash = hashLocalPassword(account, input.password)
      existing.passwordUpdatedAt = new Date().toISOString()
    } else if (previousAccount !== account) existing.passwordHash = hashLocalPassword(account, DEFAULT_LOCAL_PASSWORD)
  } else {
    authState.users.push({
      id: `user-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
      name, account, role: input.role, status: input.status,
      passwordHash: hashLocalPassword(account, input.password || DEFAULT_LOCAL_PASSWORD), passwordUpdatedAt: new Date().toISOString(),
    })
  }
  if (previousAccount === authState.currentAccount) { authState.currentAccount = account; persistSession() }
  persistUsers()
}

export function updateAuthUserRole(id: string, role: RoleKey) {
  const user = authState.users.find(item => item.id === id)
  if (!user) return
  user.role = role
  persistUsers()
}

export function updateAuthUserStatus(id: string, status: AccountStatus) {
  const user = authState.users.find(item => item.id === id)
  if (!user) return
  if (user.account === authState.currentAccount && status === 'disabled') throw new Error('不能停用当前登录账号')
  user.status = status
  persistUsers()
}

export function resetAuthUserPassword(id: string, password: string) {
  const user = authState.users.find(item => item.id === id)
  if (!user) throw new Error('账号不存在')
  const passwordError = validatePassword(password)
  if (passwordError) throw new Error(passwordError)
  user.passwordHash = hashLocalPassword(user.account, password)
  user.passwordUpdatedAt = new Date().toISOString()
  persistUsers()
}
