import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { initializeLogisticsRepository } from './data/logisticsRepository'
import { initializeAuth, isAuthenticated } from './data/authStore'
import { hydrateFinanceSettings } from './services/financeSettings'

async function bootstrap() {
  await initializeAuth()
  if (isAuthenticated.value) await Promise.all([initializeLogisticsRepository(), hydrateFinanceSettings()])
  createApp(App).use(router).mount('#app')
}

void bootstrap()
