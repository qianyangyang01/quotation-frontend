<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { defaultHomeForRole, login } from '@/data/authStore'

const router = useRouter()
const account = ref('QYY001')
const password = ref('')
const passwordVisible = ref(false)
const error = ref('')
const loading = ref(false)

async function submit() {
  error.value = ''
  if (!account.value.trim() || !password.value) { error.value = '请输入账号和密码'; return }
  loading.value = true
  const result = await login(account.value, password.value)
  if (!result.ok) { error.value = result.message; loading.value = false; return }
  await router.replace(result.user.mustChangePassword ? '/change-password' : defaultHomeForRole(result.user.role))
}
</script>

<template>
  <main class="login-page">
    <div class="aurora aurora-one" aria-hidden="true"></div>
    <div class="aurora aurora-two" aria-hidden="true"></div>
    <section class="login-brand-panel">
      <div class="tech-grid" aria-hidden="true"></div>
      <div class="data-orbit orbit-one" aria-hidden="true"><i></i><i></i><i></i></div>
      <div class="data-orbit orbit-two" aria-hidden="true"><i></i><i></i></div>
      <div class="brand"><i>M</i><span><b>米莱诺报价</b><small>MILANO PRICING ERP</small></span></div>
      <div class="brand-copy">
        <p>SMART PRICING ERP</p>
        <h1>报价系统</h1>
        <i class="brand-line"></i>
        <span>让采购、物流、财务在同一套规则里完成报价</span>
        <small>统一数据标准，连接业务流程，让每一次报价更准确、更高效、更可追溯。</small>
      </div>
      <footer>Pricing Operations Center · 米莱诺报价系统</footer>
    </section>
    <section class="login-form-panel">
      <form class="login-card" @submit.prevent="submit">
        <header><p>欢迎登录</p><h2>米莱诺报价系统</h2><span>使用管理员分配的账号和密码登录</span></header>
        <label><span>登录账号</span><div><i aria-hidden="true">⌁</i><input v-model.trim="account" autocomplete="username" placeholder="请输入账号" @input="error=''" /></div></label>
        <label><span>登录密码</span><div><i aria-hidden="true">◆</i><input v-model="password" :type="passwordVisible?'text':'password'" autocomplete="current-password" placeholder="请输入密码" @input="error=''" /><button type="button" @click="passwordVisible=!passwordVisible">{{ passwordVisible?'隐藏':'显示' }}</button></div></label>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <button class="submit" type="submit" :disabled="loading"><span>{{ loading?'正在登录…':'登录系统' }}</span><i aria-hidden="true">→</i></button>
        <aside><i aria-hidden="true">i</i><span><b>企业账号安全</b><small>请使用管理员分配的独立报价系统账号</small><small>首次登录后请立即修改临时密码</small></span></aside>
        <footer>报价系统独立服务器认证 · 会话与培训系统完全隔离</footer>
      </form>
    </section>
  </main>
</template>

<style scoped>
:global(html),:global(body),:global(#app){min-height:100%;margin:0}
.login-page{--orange:#ff9900;position:relative;display:grid;min-height:100vh;overflow:hidden;grid-template-columns:55% 45%;background:linear-gradient(135deg,#061526 0%,#0b2036 50%,#102a43 100%);color:#fff;font-family:Inter,"PingFang SC","Microsoft YaHei",sans-serif}.login-page:after{position:absolute;inset:0;pointer-events:none;background:linear-gradient(115deg,rgba(255,153,0,.025),transparent 32%,rgba(255,106,0,.04) 63%,transparent);background-size:200% 200%;content:"";animation:backgroundShift 14s ease-in-out infinite}.aurora{position:absolute;z-index:0;border-radius:50%;pointer-events:none;filter:blur(12px)}.aurora-one{top:-24%;left:23%;width:520px;height:520px;background:radial-gradient(circle,rgba(255,132,0,.18),rgba(255,132,0,0) 68%);animation:floatLight 11s ease-in-out infinite}.aurora-two{right:-12%;bottom:-32%;width:650px;height:650px;background:radial-gradient(circle,rgba(28,104,166,.25),rgba(28,104,166,0) 70%);animation:floatLight 14s ease-in-out infinite reverse}.login-brand-panel{position:relative;z-index:1;display:flex;overflow:hidden;box-sizing:border-box;min-height:100vh;flex-direction:column;padding:50px 60px 36px;border-right:1px solid rgba(255,255,255,.07)}.tech-grid{position:absolute;inset:0;opacity:.2;background-image:linear-gradient(rgba(116,158,191,.13) 1px,transparent 1px),linear-gradient(90deg,rgba(116,158,191,.13) 1px,transparent 1px);background-size:58px 58px;mask-image:linear-gradient(to bottom right,#000 10%,transparent 72%)}.data-orbit{position:absolute;border:1px solid rgba(255,153,0,.2);border-radius:50%;transform:rotate(-18deg)}.data-orbit:before,.data-orbit:after{position:absolute;height:1px;background:linear-gradient(90deg,transparent,rgba(255,153,0,.85),transparent);content:""}.data-orbit i{position:absolute;width:6px;height:6px;border-radius:50%;background:#ff9900;box-shadow:0 0 16px rgba(255,153,0,.9)}.orbit-one{right:-16%;bottom:3%;width:570px;height:300px}.orbit-one:before{top:39%;left:-10%;width:120%}.orbit-one:after{top:62%;left:5%;width:88%}.orbit-one i:nth-child(1){top:38%;left:17%}.orbit-one i:nth-child(2){top:60%;right:25%}.orbit-one i:nth-child(3){top:22%;right:15%}.orbit-two{right:9%;top:18%;width:260px;height:130px;opacity:.55}.orbit-two:before{top:50%;left:-18%;width:130%}.orbit-two i:first-child{top:47%;left:20%}.orbit-two i:last-child{top:48%;right:10%}.brand{position:relative;z-index:2;display:flex;align-items:center;gap:13px}.brand>i{display:grid;width:48px;height:48px;place-items:center;border-radius:12px;background:linear-gradient(145deg,#ffac1c,#ff7b00);color:#061526;font-size:25px;font-style:normal;font-weight:950;box-shadow:0 10px 28px rgba(255,129,0,.22)}.brand>span{display:grid}.brand b{font-size:19px;letter-spacing:.5px}.brand small{margin-top:2px;color:#aab4c3;font-size:8px;letter-spacing:2.2px}.brand-copy{position:relative;z-index:2;margin:auto 0 auto 3.5vw;transform:translateY(4vh)}.brand-copy p{margin:0;color:#ff9b09;font-size:10px;font-weight:900;letter-spacing:3px}.brand-copy h1{margin:18px 0 13px;font-size:clamp(42px,4vw,58px);line-height:1.1;letter-spacing:1px}.brand-line{display:block;width:56px;height:4px;margin-bottom:24px;border-radius:5px;background:linear-gradient(90deg,#ff9900,#ff6a00);box-shadow:0 0 18px rgba(255,135,0,.45)}.brand-copy>span{display:block;max-width:630px;color:#f5f8fb;font-size:clamp(16px,1.35vw,20px);line-height:1.7}.brand-copy>small{display:block;max-width:550px;margin-top:18px;color:#aab4c3;font-size:12px;line-height:1.9}.login-brand-panel>footer{position:relative;z-index:2;color:#8294a7;font-size:9px;letter-spacing:1px}.login-form-panel{position:relative;z-index:2;display:flex;box-sizing:border-box;min-height:100vh;align-items:center;justify-content:flex-end;padding:40px clamp(48px,6.2vw,120px) 40px 42px}.login-card{box-sizing:border-box;width:min(420px,100%);padding:45px;border:1px solid rgba(255,255,255,.15);border-radius:24px;background:rgba(255,255,255,.075);box-shadow:0 28px 80px rgba(0,5,14,.38),inset 0 1px 0 rgba(255,255,255,.08);backdrop-filter:blur(20px);animation:cardIn .65s cubic-bezier(.2,.8,.2,1) both}.login-card header{margin-bottom:28px}.login-card header p{margin:0;color:#ff9900;font-size:12px;font-weight:800;letter-spacing:1.8px}.login-card h2{margin:9px 0 8px;color:#fff;font-size:28px;line-height:1.25}.login-card header span{color:#aab4c3;font-size:11px}.login-card label{display:grid;gap:8px;margin-top:18px;color:#ccd5df;font-size:10px;font-weight:700}.login-card label>div{display:flex;box-sizing:border-box;height:52px;align-items:center;border:1px solid rgba(255,255,255,.14);border-radius:10px;background:rgba(1,13,27,.48);transition:border-color .2s,box-shadow .2s,background .2s}.login-card label>div:focus-within{border-color:rgba(255,153,0,.8);background:rgba(1,13,27,.68);box-shadow:0 0 0 3px rgba(255,153,0,.11),0 10px 28px rgba(0,0,0,.14)}.login-card label i{display:grid;width:48px;place-items:center;color:#ff9900;font-size:13px;font-style:normal}.login-card input{min-width:0;flex:1;border:0;outline:0;background:transparent;color:#fff;font-family:inherit;font-size:12px}.login-card input::placeholder{color:#728195}.login-card label button{height:100%;padding:0 15px;border:0;background:transparent;color:#d8a252;font-size:9px;cursor:pointer}.error{margin:12px 0 0;padding:9px 11px;border:1px solid rgba(255,95,83,.25);border-radius:8px;background:rgba(183,43,34,.16);color:#ff9b92;font-size:10px}.submit{display:flex;width:100%;height:52px;align-items:center;justify-content:center;gap:14px;margin-top:22px;border:0;border-radius:10px;background:linear-gradient(105deg,#ff9900,#ff6a00);color:#071526;font-family:inherit;font-size:12px;font-weight:950;cursor:pointer;box-shadow:0 12px 28px rgba(255,105,0,.22);transition:transform .2s,box-shadow .2s,filter .2s}.submit i{font-size:18px;font-style:normal;transition:transform .2s}.submit:hover:not(:disabled){transform:translateY(-2px) scale(1.012);filter:brightness(1.06);box-shadow:0 17px 34px rgba(255,105,0,.32)}.submit:hover:not(:disabled) i{transform:translateX(3px)}.submit:disabled{cursor:wait;opacity:.62}.login-card aside{display:flex;gap:12px;margin-top:20px;padding:13px 14px;border:1px solid rgba(255,153,0,.34);border-radius:10px;background:rgba(3,17,31,.48);color:#bbc5d0}.login-card aside>i{display:grid;width:22px;height:22px;flex:0 0 22px;place-items:center;border:1px solid #ff9900;border-radius:50%;color:#ff9900;font-size:10px;font-style:normal;font-weight:900}.login-card aside>span{display:grid;gap:4px}.login-card aside b{color:#f4f7fa;font-size:10px}.login-card aside small{color:#aab4c3;font-size:9px}.login-card>footer{margin-top:19px;color:#718196;font-size:8px;text-align:center}@keyframes backgroundShift{0%,100%{background-position:0 50%}50%{background-position:100% 50%}}@keyframes floatLight{0%,100%{transform:translate3d(0,0,0) scale(1)}50%{transform:translate3d(30px,18px,0) scale(1.08)}}@keyframes cardIn{from{opacity:0;transform:translateY(20px) scale(.985)}to{opacity:1;transform:translateY(0) scale(1)}}@media(max-height:820px){.login-card{padding:34px 38px}.login-card header{margin-bottom:20px}.login-card label{margin-top:14px}.login-card aside{margin-top:15px}.login-card>footer{margin-top:13px}.brand-copy{transform:none}}@media(max-width:1100px){.login-page{grid-template-columns:50% 50%}.login-brand-panel{padding-right:38px}.brand-copy{margin-left:0}.login-form-panel{padding-right:42px}}@media(max-width:820px){.login-page{display:block}.login-brand-panel{position:absolute;inset:0;min-height:100vh;padding:28px 26px}.login-brand-panel .brand{transform:scale(.9);transform-origin:left top}.brand-copy,.login-brand-panel>footer{display:none}.login-form-panel{min-height:100vh;justify-content:center;padding:88px 22px 24px}.login-card{background:rgba(7,27,45,.8)}}@media(max-width:480px){.login-card{padding:30px 22px;border-radius:20px}.login-card h2{font-size:24px}.login-form-panel{padding-right:15px;padding-left:15px}}@media(prefers-reduced-motion:reduce){.login-page:after,.aurora,.login-card{animation:none}.submit{transition:none}}
/* Reference-image background and near-transparent glass treatment. */
.login-page{background-color:#061526;background-image:linear-gradient(90deg,rgba(3,15,28,.08) 0%,rgba(3,15,28,.02) 54%,rgba(2,12,24,.22) 100%),url("@/assets/login-world-network-bg-v2.webp");background-position:center;background-size:cover}
.login-page:after{background:linear-gradient(115deg,rgba(0,8,18,.03),transparent 46%,rgba(0,8,18,.14));animation:none}
.aurora,.tech-grid,.data-orbit{display:none}
.login-brand-panel{border-right:0}
.brand-copy{margin-left:0;transform:translateY(12vh);text-shadow:0 3px 24px rgba(0,5,14,.75)}
.login-card{border-color:rgba(255,255,255,.32);background:rgba(2,15,28,.08);box-shadow:0 24px 60px rgba(0,5,14,.18),inset 0 1px 0 rgba(255,255,255,.08);backdrop-filter:blur(4px) saturate(115%)}
.login-card label>div{border-color:rgba(255,255,255,.22);background:rgba(1,13,27,.2)}
.login-card aside{border-color:rgba(255,153,0,.44);background:rgba(3,17,31,.14)}
@media(max-height:820px){.brand-copy{transform:none}}
@media(max-width:820px){.login-page{background-position:42% center}.login-card{background:rgba(2,15,28,.035);backdrop-filter:blur(1.5px) saturate(110%)}}
.login-page{background-image:linear-gradient(90deg,rgba(3,15,28,.035) 0%,rgba(3,15,28,.01) 55%,rgba(2,12,24,.1) 100%),url("@/assets/login-world-network-bg-v2.webp")}
.login-card{border-color:rgba(255,255,255,.42);background:rgba(2,15,28,.025);box-shadow:0 18px 50px rgba(0,5,14,.12),inset 0 1px 0 rgba(255,255,255,.16);backdrop-filter:blur(1.5px) saturate(110%)}
.login-card label>div{border-color:rgba(255,255,255,.32);background:rgba(1,13,27,.025);box-shadow:inset 0 1px 0 rgba(255,255,255,.05);backdrop-filter:blur(1px)}
.login-card label>div:focus-within{background:rgba(1,13,27,.07)}
.login-card aside{background:rgba(3,17,31,.025);backdrop-filter:blur(1px)}
</style>
