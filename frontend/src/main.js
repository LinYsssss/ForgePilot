import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router.js'
import { listNav } from './directives/listNav.js'
import './tokens.css'

createApp(App).use(router).directive('list-nav', listNav).mount('#app')
