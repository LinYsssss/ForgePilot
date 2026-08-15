import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router.js'
import { listNav } from './directives/listNav.js'
import { API_BASE } from './api/client.js'
import { installClientErrorReporter } from './shared/telemetry/clientErrorReporter.js'
import './tokens.css'
import './shared/theme/index.js'

// 先挂上报再建应用:挂载过程本身抛出的错误也要能被捕获。
installClientErrorReporter(`${API_BASE}/client-errors`)

createApp(App).use(router).directive('list-nav', listNav).mount('#app')
