<template>
  <!-- 墨境隔离入口(meta.shell === 'ink'):页面自带 LoginGate 门禁与新壳层,
       不经过旧 LoginView/AppShell;会话与 401 漏斗仍是下面同一套单例。 -->
  <router-view v-if="isInkShell" />

  <LoginView v-else-if="!authenticated" @authenticated="afterLogin" />

  <AppShell v-else @navigate="onNavigate" @refresh="run(refreshAll)" @logout="logout">
    <router-view v-slot="{ Component }">
      <transition name="page" mode="out-in">
        <component :is="Component" />
      </transition>
    </router-view>
  </AppShell>
</template>

<script setup>
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { initCsrf, setUnauthorizedHandler } from './api/client.js'
import AppShell from './components/AppShell.vue'
import LoginView from './views/LoginView.vue'
import { useSession } from './composables/useSession.js'
import { useBusy } from './composables/useBusy.js'
import { useToast } from './composables/useToast.js'
import { useReviews } from './composables/useReviews.js'
import { useAgentWorkspace } from './composables/useAgentWorkspace.js'
import { useWorkspace } from './composables/useWorkspace.js'

// App.vue 只负责登录态分流与全局生命周期;
// 领域状态在 composables/,页面在 views/,跨域动作在 useWorkspace。
const route = useRoute()
const isInkShell = computed(() => route.meta.shell === 'ink')
const { authenticated, me } = useSession()
const { run } = useBusy()
const { toastMsg } = useToast()
const { stopPolling } = useReviews()
const { stopAgentPolling } = useAgentWorkspace()
const { goto, afterLogin, logout, loadMe, refreshAll, goTab, openAgentWorkspace, openProjectAiLogs } = useWorkspace()

// 侧边导航统一入口:agent / aiLogs 各有装载动作,dashboard/projects 直切不刷新,
// 其余(依赖当前项目数据的页)切换时按需刷新 —— 与拆分前行为一致。
function onNavigate(name) {
  if (name === 'agent') return openAgentWorkspace()
  if (name === 'aiLogs') return openProjectAiLogs()
  if (name === 'dashboard' || name === 'projects') return goto(name)
  goTab(name)
}

// 会话失效只处理一次:并发请求同时 401 时,第一个把 authenticated 置 false,其余直接短路。
setUnauthorizedHandler(() => {
  if (!authenticated.value) return
  logout(false)
  toastMsg('登录已过期，请重新登录', 'error')
})

onMounted(async () => {
  // 先做 CSRF 引导:拿到开关状态与 Cookie 名,之后的写请求才知道要不要带 X-XSRF-TOKEN。
  await initCsrf()
  try {
    await loadMe()
    authenticated.value = !!me.userId
    if (authenticated.value) {
      await refreshAll()
    }
  } catch {
    authenticated.value = false
  }
})
onUnmounted(() => { stopPolling(); stopAgentPolling() })
</script>
