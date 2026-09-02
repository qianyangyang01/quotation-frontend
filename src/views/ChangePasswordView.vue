<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { changeCurrentPassword, defaultHomeForRole, validatePassword } from '@/data/authStore'

const router = useRouter()
const form = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const error = ref('')
const loading = ref(false)

async function submit() {
  error.value = ''
  const passwordError = validatePassword(form.newPassword)
  if (passwordError) { error.value = passwordError; return }
  if (form.newPassword !== form.confirmPassword) { error.value = '两次输入的新密码不一致'; return }
  loading.value = true
  try { await changeCurrentPassword(form.currentPassword, form.newPassword); await router.replace(defaultHomeForRole()) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '密码修改失败' }
  finally { loading.value = false }
}
</script>

<template>
  <main class="password-page"><form @submit.prevent="submit"><small>ACCOUNT SECURITY</small><h1>修改临时密码</h1><p>这是报价系统的独立账号。首次登录必须设置新密码后才能进入业务模块。</p><label>当前临时密码<input v-model="form.currentPassword" type="password" autocomplete="current-password"></label><label>新密码<input v-model="form.newPassword" type="password" autocomplete="new-password" placeholder="至少10位，包含字母和数字"></label><label>确认新密码<input v-model="form.confirmPassword" type="password" autocomplete="new-password"></label><em v-if="error" role="alert">{{ error }}</em><button :disabled="loading">{{ loading ? '正在保存…' : '保存并进入报价系统' }}</button></form></main>
</template>

<style scoped>
.password-page{display:grid;min-height:100vh;place-items:center;padding:24px;background:#071a2d;color:#162633;font-family:"Microsoft YaHei",sans-serif}.password-page form{box-sizing:border-box;width:min(460px,100%);padding:34px;border-radius:16px;background:#fff;box-shadow:0 24px 70px rgba(0,0,0,.3)}small{color:#d87900;font-weight:900;letter-spacing:2px}h1{margin:8px 0 10px;font-size:25px}p{color:#687884;font-size:12px;line-height:1.8}label{display:grid;gap:7px;margin-top:16px;color:#53636f;font-size:11px;font-weight:800}input{height:43px;padding:0 12px;border:1px solid #d4dde3;border-radius:8px;font:inherit}em{display:block;margin-top:12px;color:#c34037;font-size:11px;font-style:normal}button{width:100%;height:44px;margin-top:22px;border:0;border-radius:8px;background:#ff9810;color:#142431;font-weight:900;cursor:pointer}button:disabled{opacity:.55}
</style>
