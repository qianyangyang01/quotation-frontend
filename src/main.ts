import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { initializeLogisticsRepository } from './data/logisticsRepository'

async function bootstrap() {
  await initializeLogisticsRepository()
  createApp(App).use(router).mount('#app')
}

void bootstrap()
