import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { initializeAuth, isAuthenticated } from './data/authStore'
import { hydrateFinanceSettings } from './services/financeSettings'

async function bootstrap() {
  await initializeAuth()
  if (isAuthenticated.value) await hydrateFinanceSettings().catch(() => undefined)
  createApp(App).use(router).mount('#app')
}

void bootstrap()
