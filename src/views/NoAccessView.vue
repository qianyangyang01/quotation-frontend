<template>
  <main class="no-access-page">
    <section aria-labelledby="no-access-title">
      <span class="status-icon" aria-hidden="true">—</span>
      <h1 id="no-access-title">暂无业务权限</h1>
      <p>当前账号暂未分配业务权限，请联系管理员。</p>
      <small>如需切换账号，可通过右上角账号菜单退出登录。</small>
    </section>
  </main>
</template>

<style scoped>
.no-access-page{display:grid;box-sizing:border-box;min-height:calc(100vh - 72px);place-items:center;padding:32px 24px;background:#f3f6f8;color:#172431;font-family:"Microsoft YaHei",sans-serif}.no-access-page section{box-sizing:border-box;width:min(520px,100%);padding:40px 28px;border:1px solid #dfe5e9;border-radius:13px;background:#fff;text-align:center}.status-icon{display:grid;width:48px;height:48px;margin:0 auto 20px;place-items:center;border-radius:50%;background:#fff3df;color:#c97400;font-size:24px}.no-access-page h1{margin:0 0 16px;font-size:26px}.no-access-page p{margin:0;color:#53616c;font-size:14px;line-height:1.8}.no-access-page small{display:block;margin-top:16px;color:#74818a;font-size:12px;line-height:1.8}
</style>
