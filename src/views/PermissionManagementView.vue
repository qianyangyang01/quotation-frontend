<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  authState,
  currentAuthUser,
  loadAuthUsers,
  resetAuthUserPassword,
  roleDefinitions,
  saveAuthUser,
  updateAuthUserRole,
  updateAuthUserStatus,
  type AccountStatus,
  type RoleKey,
} from '@/data/authStore'

const keyword = ref('')
const roleFilter = ref<RoleKey | ''>('')
const notice = ref('')
const showAdd = ref(false)
const loading = ref(true)
const loadError = ref('')
const adding = ref(false)
const addError = ref('')
const form = reactive({ name: '', account: '', password: '', confirmPassword: '', role: 'employee' as RoleKey })
const resetUserId = ref('')
const resetForm = reactive({ password: '', confirmPassword: '' })
const filteredUsers = computed(() => authState.users.filter(user => {
  const query = keyword.value.trim().toLowerCase()
  return (!query || `${user.name} ${user.account}`.toLowerCase().includes(query)) && (!roleFilter.value || user.role === roleFilter.value)
}))
const enabledCount = computed(() => authState.users.filter(user => user.status === 'enabled').length)
function roleName(role: RoleKey) { return roleDefinitions.find(item => item.key === role)?.name || role }
function permissionLabel(permission: string, short = false) {
  const labels: Record<string, [string, string]> = {
    quote: ['我的报价', '报价'], purchase: ['采购', '采购'], logistics: ['物流', '物流'], finance: ['财务', '财务'],
    myRecords: ['我的报价记录', '本人记录'], allRecords: ['报价记录', '全部记录'], permissions: ['权限管理', '权限'],
  }
  return labels[permission]?.[short ? 1 : 0] || permission
}
function toast(message: string) { notice.value = message; window.setTimeout(() => notice.value === message && (notice.value = ''), 2400) }
async function changeRole(id: string, role: RoleKey) {
  const user = authState.users.find(item => item.id === id)
  if (!user) return
  if (user.account === currentAuthUser.value.account) { toast('当前登录账号不能在本页修改自身角色'); return }
  try { await updateAuthUserRole(id, role); toast(`已将${user.name}调整为${roleName(role)}`) }
  catch (error) { toast(error instanceof Error ? error.message : '角色修改失败') }
}
async function changeStatus(id: string, status: AccountStatus) {
  try { await updateAuthUserStatus(id, status); toast(status === 'enabled' ? '账号已启用' : '账号已停用') }
  catch (error) { toast(error instanceof Error ? error.message : '操作失败') }
}
async function addUser() {
  if (adding.value) return
  adding.value = true; addError.value = ''
  try {
    if (form.password !== form.confirmPassword) throw new Error('两次输入的密码不一致')
    await saveAuthUser({ name: form.name, account: form.account, password: form.password, role: form.role, status: 'enabled' })
    form.name = ''; form.account = ''; form.password = ''; form.confirmPassword = ''; form.role = 'employee'; showAdd.value = false
    toast('账号已添加')
  } catch (error) { addError.value = error instanceof Error && error.name === 'TimeoutError' ? '提交超时，请先刷新账号列表确认是否已创建，再重试。' : error instanceof Error ? error.message : '添加失败' }
  finally { adding.value = false }
}
function openResetPassword(id: string) {
  resetUserId.value = id
  resetForm.password = ''
  resetForm.confirmPassword = ''
}
function closeResetPassword() {
  resetUserId.value = ''
  resetForm.password = ''
  resetForm.confirmPassword = ''
}
async function submitResetPassword() {
  try {
    if (resetForm.password !== resetForm.confirmPassword) throw new Error('两次输入的密码不一致')
    await resetAuthUserPassword(resetUserId.value, resetForm.password)
    closeResetPassword()
    toast('密码已重置，原密码立即失效')
  } catch (error) { toast(error instanceof Error ? error.message : '密码重置失败') }
}
async function refreshUsers() {
  loading.value = true; loadError.value = ''
  try { await loadAuthUsers() }
  catch (error) { loadError.value = error instanceof Error && error.name === 'TimeoutError' ? '账号列表加载超时，请重试。' : error instanceof Error ? error.message : '账号列表加载失败' }
  finally { loading.value = false }
}
onMounted(refreshUsers)
</script>

<template>
  <div class="permission-page">
    <main>
      <header class="page-head"><div><p>ACCESS CONTROL</p><h1>权限管理</h1><span>按岗位分配可见模块；页面入口和直接地址访问使用同一套权限规则。</span></div><button @click="showAdd=!showAdd">＋ 新增账号</button></header>
      <section class="summary"><article><small>角色数量</small><b>5</b><span>按岗位预设权限</span></article><article><small>账号数量</small><b>{{ authState.users.length }}</b><span>报价服务器账号</span></article><article><small>启用账号</small><b>{{ enabledCount }}</b><span>可进入系统</span></article><article><small>当前角色</small><b>{{ roleName(currentAuthUser.role) }}</b><span>{{ currentAuthUser.account }}</span></article></section>

      <section class="roles-card"><header><div><h2>角色权限</h2><span>财务现阶段与超级管理员使用相同业务权限</span></div></header><div class="role-grid"><article v-for="role in roleDefinitions" :key="role.key"><header><i>{{ role.shortName.slice(0,1) }}</i><span><b>{{ role.name }}</b><small>{{ authState.users.filter(user=>user.role===role.key).length }} 个账号</small></span></header><p>{{ role.description }}</p><div><em v-for="permission in role.permissions" :key="permission">{{ permissionLabel(permission) }}</em></div></article></div></section>

      <section v-if="showAdd" class="add-card"><header><div><h2>新增账号</h2><span>创建后员工可使用账号和临时密码直接登录</span></div><button aria-label="关闭" @click="showAdd=false">×</button></header><div><label>姓名<input v-model.trim="form.name" autocomplete="off" placeholder="输入员工姓名"></label><label>登录账号<input v-model.trim="form.account" autocomplete="off" placeholder="例如 YW002"></label><label>初始密码<input v-model="form.password" type="password" autocomplete="new-password" placeholder="至少10位，包含字母和数字"></label><label>确认密码<input v-model="form.confirmPassword" type="password" autocomplete="new-password" placeholder="再次输入初始密码"></label><label>角色<select v-model="form.role"><option v-for="role in roleDefinitions" :key="role.key" :value="role.key">{{ role.name }}</option></select></label><button :disabled="adding" @click="addUser">{{ adding ? "正在创建…" : "确认添加" }}</button></div></section>

      <p v-if="addError" role="alert" class="error-message">{{ addError }}</p><p v-if="loading" role="status">正在加载账号列表…</p><p v-if="loadError" role="alert" class="error-message">{{ loadError }} <button @click="refreshUsers">重新加载</button></p><section class="accounts-card"><header><div><h2>账号与角色</h2><span>修改后立即影响该账号的菜单和路由访问</span></div><div><label>⌕<input v-model="keyword" placeholder="搜索姓名或账号"></label><select v-model="roleFilter"><option value="">全部角色</option><option v-for="role in roleDefinitions" :key="role.key" :value="role.key">{{ role.name }}</option></select></div></header><div class="table-scroll"><table><thead><tr><th>账号</th><th>当前角色</th><th>可见范围</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="user in filteredUsers" :key="user.id"><td><span class="person"><i>{{ user.name.slice(0,2) }}</i><span><b>{{ user.name }}</b><small>{{ user.account }}</small></span></span></td><td><select :value="user.role" :disabled="user.account===currentAuthUser.account" @change="changeRole(user.id,($event.target as HTMLSelectElement).value as RoleKey)"><option v-for="role in roleDefinitions" :key="role.key" :value="role.key">{{ role.name }}</option></select></td><td><span class="scope-tags"><em v-for="permission in roleDefinitions.find(role=>role.key===user.role)?.permissions" :key="permission">{{ permissionLabel(permission,true) }}</em></span></td><td><span :class="['status',user.status]">{{ user.status==='enabled'?'已启用':'已停用' }}</span></td><td><span class="row-actions"><button class="reset" @click="openResetPassword(user.id)">重置密码</button><button v-if="user.status==='enabled'" :disabled="user.account===currentAuthUser.account" class="disable" @click="changeStatus(user.id,'disabled')">停用</button><button v-else class="enable" @click="changeStatus(user.id,'enabled')">启用</button></span></td></tr><tr v-if="!loading && !loadError && !filteredUsers.length"><td colspan="5" class="empty">没有找到匹配账号</td></tr></tbody></table></div></section>
    </main>
    <div v-if="resetUserId" class="modal-mask" @click.self="closeResetPassword"><section class="password-modal" role="dialog" aria-modal="true" aria-label="重置密码"><header><div><small>ACCOUNT SECURITY</small><h2>重置登录密码</h2><span>{{ authState.users.find(user=>user.id===resetUserId)?.name }} · {{ authState.users.find(user=>user.id===resetUserId)?.account }}</span></div><button aria-label="关闭" @click="closeResetPassword">×</button></header><label>新密码<input v-model="resetForm.password" type="password" autocomplete="new-password" placeholder="至少10位，包含字母和数字"></label><label>确认新密码<input v-model="resetForm.confirmPassword" type="password" autocomplete="new-password" placeholder="再次输入新密码"></label><p>保存后，该账号原密码将立即失效，并要求首次登录修改。</p><footer><button @click="closeResetPassword">取消</button><button class="primary" @click="submitResetPassword">确认重置</button></footer></section></div>
    <div v-if="notice" class="toast">{{ notice }}</div>
  </div>
</template>

<style scoped>
.error-message{padding:12px;border:1px solid #efb8b8;border-radius:8px;background:#fff3f3;color:#9c2424}
.permission-page{min-height:100vh;background:#f3f6f8;color:#152431;font-family:"Microsoft YaHei",sans-serif}.permission-page main{width:min(1480px,calc(100% - 48px));margin:auto;padding:34px 0 70px}.page-head{display:flex;align-items:flex-end;justify-content:space-between;margin-bottom:22px}.page-head p{margin:0;color:#dc7900;font-size:9px;font-weight:900;letter-spacing:2px}.page-head h1{margin:5px 0 8px;font-size:28px}.page-head span,.roles-card>header span,.accounts-card>header span,.add-card>header span{color:#74818b;font-size:11px}.page-head>button,.add-card>div>button{height:42px;padding:0 22px;border:0;border-radius:8px;background:#ff9810;color:#172431;font-weight:900;cursor:pointer}.summary{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:14px}.summary article,.roles-card,.accounts-card,.add-card{border:1px solid #dde4e9;border-radius:12px;background:#fff;box-shadow:0 8px 25px rgba(34,52,66,.035)}.summary article{display:grid;gap:4px;padding:18px}.summary small{color:#74818b;font-size:10px}.summary b{font-size:25px}.summary span{color:#8d979f;font-size:9px}.roles-card,.accounts-card{padding:18px;margin-top:14px}.roles-card>header,.accounts-card>header,.add-card>header{display:flex;align-items:center;justify-content:space-between;margin-bottom:14px}.roles-card h2,.accounts-card h2,.add-card h2{margin:0 0 4px;font-size:16px}.role-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:10px}.role-grid article{min-height:168px;padding:14px;border:1px solid #e1e7eb;border-radius:10px;background:#fbfcfd}.role-grid article>header{display:flex;align-items:center;gap:9px}.role-grid article>header i{display:grid;width:34px;height:34px;place-items:center;border-radius:50%;background:#fff0d7;color:#c66800;font-style:normal;font-weight:900}.role-grid article>header span{display:grid}.role-grid article>header b{font-size:13px}.role-grid article>header small{color:#84909a;font-size:9px}.role-grid p{min-height:42px;color:#697782;font-size:10px;line-height:1.6}.role-grid article>div,.scope-tags{display:flex;flex-wrap:wrap;gap:4px}.role-grid em,.scope-tags em{padding:4px 6px;border-radius:10px;background:#eef3f5;color:#53636f;font-size:8px;font-style:normal}.add-card{margin-top:14px;padding:18px}.add-card>header>button{border:0;background:transparent;font-size:22px;cursor:pointer}.add-card>div{display:grid;grid-template-columns:repeat(5,minmax(150px,1fr)) auto;gap:12px;align-items:end}.add-card label{display:grid;gap:6px;color:#56656f;font-size:10px}.add-card input,.add-card select,.accounts-card input,.accounts-card select,table select{box-sizing:border-box;height:38px;padding:0 11px;border:1px solid #d6dee4;border-radius:7px;background:#fff;color:#1b2a35}.accounts-card>header>div:last-child{display:flex;gap:8px}.accounts-card>header label{display:flex;height:38px;align-items:center;gap:7px;padding:0 10px;border:1px solid #d6dee4;border-radius:7px;color:#84909a}.accounts-card>header label input{height:34px;padding:0;border:0;outline:0}.table-scroll{overflow:auto}table{width:100%;border-collapse:collapse}th{padding:11px 12px;background:#f3f6f8;color:#687782;font-size:9px;text-align:left}td{padding:12px;border-bottom:1px solid #edf1f3;font-size:10px}.person{display:flex;align-items:center;gap:9px}.person>i{display:grid;width:34px;height:34px;place-items:center;border-radius:50%;background:#162532;color:#fff;font-size:9px;font-style:normal}.person>span{display:grid}.person small{color:#86919a;font-size:8px}.status{display:inline-flex;padding:5px 8px;border-radius:10px;font-size:9px;font-weight:900}.status.enabled{background:#e4f6eb;color:#15804c}.status.disabled{background:#edf0f2;color:#67737c}.row-actions{display:flex;align-items:center;gap:10px;white-space:nowrap}.row-actions button{border:0;background:transparent;font-size:10px;font-weight:900;cursor:pointer}.row-actions button.reset{color:#b56800}.row-actions button.disable{color:#d14e45}.row-actions button.enable{color:#13804b}.row-actions button:disabled{color:#adb5bb;cursor:not-allowed}.empty{height:100px;color:#89949c;text-align:center}.modal-mask{position:fixed;z-index:80;inset:0;display:grid;place-items:center;padding:24px;background:rgba(11,23,32,.55);backdrop-filter:blur(5px)}.password-modal{box-sizing:border-box;width:min(440px,100%);padding:24px;border-radius:14px;background:#fff;box-shadow:0 24px 70px rgba(0,0,0,.22)}.password-modal>header{display:flex;align-items:flex-start;justify-content:space-between;margin-bottom:20px}.password-modal>header small{color:#d37700;font-size:8px;font-weight:900;letter-spacing:1.5px}.password-modal h2{margin:5px 0;font-size:20px}.password-modal>header span{color:#7b8790;font-size:10px}.password-modal>header button{border:0;background:transparent;font-size:22px;cursor:pointer}.password-modal>label{display:grid;gap:7px;margin-top:13px;color:#56656f;font-size:10px}.password-modal input{height:40px;padding:0 11px;border:1px solid #d6dee4;border-radius:7px}.password-modal>p{margin:13px 0 0;color:#c04c43;font-size:9px}.password-modal>footer{display:flex;justify-content:flex-end;gap:9px;margin-top:22px}.password-modal>footer button{height:38px;padding:0 17px;border:1px solid #d6dee4;border-radius:7px;background:#fff;font-weight:900;cursor:pointer}.password-modal>footer button.primary{border-color:#ff9810;background:#ff9810;color:#172431}.toast{position:fixed;z-index:100;right:28px;bottom:28px;padding:12px 18px;border-radius:8px;background:#172431;color:#fff;font-size:11px;box-shadow:0 12px 30px rgba(0,0,0,.18)}@media(max-width:1250px){.add-card>div{grid-template-columns:repeat(3,1fr)}.role-grid{grid-template-columns:repeat(2,1fr)}.summary{grid-template-columns:repeat(2,1fr)}}@media(max-width:720px){.permission-page main{width:calc(100% - 24px)}.page-head{align-items:flex-start;gap:15px}.summary,.role-grid,.add-card>div{grid-template-columns:1fr}.accounts-card>header{align-items:flex-start;gap:12px;flex-direction:column}.accounts-card>header>div:last-child{width:100%;flex-wrap:wrap}.table-scroll table{min-width:900px}}
</style>
