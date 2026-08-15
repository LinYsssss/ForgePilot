<template>
  <LoginGate v-if="!authenticated" @authenticated="onAuthenticated" />

  <InkShell
    v-else
    ref="shellEl"
    :nav-items="navItems"
    :active-key="activeKey"
    :context-label="contextLabel"
    :context-title="contextTitle || (activeProject ? activeProject.name : '未选择项目')"
    :rail-badge="railBadge"
    :rail-title="railTitle"
    :user="{ name: me.nickname || me.username, role: me.role }"
    @navigate="onNavigate"
    @logout="logout()"
  >
    <template #case><slot name="case" /></template>
    <template #index-foot><slot name="index-foot" /></template>
    <template #top-actions><slot name="top-actions" /></template>

    <slot />

    <!-- 确认弹层由页框统一承载。useConfirm 是单例,而模态此前只有 AppShell 与墨境工作台在渲染:
         迁移页若不渲染它,askDelete* 之类会「点了没反应」——是静默失败,不是报错。
         放在这里,后续每迁一页都自动具备,不必逐页记得补。 -->
    <InkDialog
      v-if="confirmModal"
      glyph="印"
      :title="confirmModal.title"
      :body="confirmModal.body"
      :confirm-label="confirmModal.confirmLabel || '确认'"
      cancel-label="返回"
      :busy="!!busy.confirm"
      @cancel="dismiss"
      @confirm="run(confirmAction)"
    />

    <template #rail><slot name="rail" /></template>
  </InkShell>
</template>

<script setup>
import { computed, ref } from 'vue'
import LoginGate from '../auth/LoginGate.vue'
import InkShell from './InkShell.vue'
import InkDialog from './InkDialog.vue'
import { navItemsFor, resolveNavigation } from './inkNav.js'
import { nav } from '../../nav.js'
import { useBusy } from '../../composables/useBusy.js'
import { useConfirm } from '../../composables/useConfirm.js'
import { useSession } from '../../composables/useSession.js'
import { useToast } from '../../composables/useToast.js'
import { useWorkspace } from '../../composables/useWorkspace.js'

// 墨境页框(实施步骤 8):把「登录门禁 + 壳层 + 侧栏导航 + 用户/登出」这套**每页都一样**的
// 外壳收成一处。逐页扩面要迁 6 个页面,外壳若跟着复制 6 份,任何一次壳层调整都要改 6 个地方,
// 而漏改的那个只会在用户点到它时才暴露。
//
// 这里只承载外壳与路由编排,不碰任何业务数据——各页自己用既有 composable 取数,
// 保持「数据逻辑零复制」这条既定约束。
const props = defineProps({
  activeKey: { type: String, required: true },
  contextLabel: { type: String, default: '当前项目' },
  contextTitle: { type: String, default: '' },
  railTitle: { type: String, default: '审查批注' },
  railBadge: { type: String, default: '' },
})
const emit = defineEmits(['authenticated'])

const { authenticated, me, activeProject } = useSession()
const { busy, run } = useBusy()
const { confirmModal, dismiss, confirmAction } = useConfirm()
const { toastMsg } = useToast()
const { loadMe, refreshAll, logout, goto, goTab, openAgentWorkspace, openProjectAiLogs } = useWorkspace()

const shellEl = ref(null)
const navItems = computed(() => navItemsFor(!!activeProject.value))

/** 决策在 inkNav(纯函数、可测),这里只负责按决策执行。 */
function onNavigate(key) {
  const decision = resolveNavigation(key, props.activeKey)
  if (decision.action === 'none') return
  if (decision.action === 'ink') return nav.push({ name: decision.routeName })
  if (decision.legacy === 'agent') return openAgentWorkspace()
  if (decision.legacy === 'aiLogs') return openProjectAiLogs()
  if (decision.legacy === 'goto') return goto(key)
  return goTab(key)
}

// 登录成功后与旧壳层 afterLogin 同源动作(loadMe + refreshAll),但停留在墨境;
// refreshAll 会装载项目并选中默认项目,各页据此 watch 装载自己的数据。
async function onAuthenticated() {
  await run(async () => {
    await loadMe()
    await refreshAll()
    toastMsg('登录成功', 'success')
  })
  emit('authenticated')
}

defineExpose({ closeDrawer: () => shellEl.value?.closeDrawer?.() })
</script>
