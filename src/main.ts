import { createApp } from 'vue'
import App from './App.vue'
import router from './router'

// Render the bootstrap shell while the router verifies authentication. Each
// workspace owns its data readiness; unrelated finance reads must not hide it.
createApp(App).use(router).mount('#app')
