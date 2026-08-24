import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { initializeAuth, isAuthenticated } from './data/authStore'
import { hydrateFinanceSettings } from './services/financeSettings'

// 先挂载品牌框架，认证和业务配置在后台加载；任何物流数据都由具体业务页面按需请求。
createApp(App).use(router).mount('#app')

void initializeAuth().then(() => {
  if (isAuthenticated.value) void hydrateFinanceSettings()
})
