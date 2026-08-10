<template>
  <el-container class="app-shell">
    <el-aside class="sidebar" width="268px">
      <div class="brand">
        <div class="brand-logo">R</div>
        <div>
          <h1>RepoSage</h1>
          <div class="tagline">Java · MQ · RAG · AI</div>
        </div>
      </div>

      <nav aria-label="主导航">
        <button :class="{ active: current === 'dashboard' }" @click="emit('navigate', 'dashboard')">
          <span class="nav-ico" aria-hidden="true">▦</span> 概览
        </button>
        <button :class="{ active: current === 'projects' }" @click="emit('navigate', 'projects')">
          <span class="nav-ico" aria-hidden="true">▤</span> 项目
        </button>
        <button :class="{ active: current === 'repository' }" @click="emit('navigate', 'repository')" :disabled="!activeProject">
          <span class="nav-ico" aria-hidden="true">⎇</span> 仓库
        </button>
        <button :class="{ active: current === 'pullRequests' }" @click="emit('navigate', 'pullRequests')" :disabled="!activeProject">
          <span class="nav-ico" aria-hidden="true">⑂</span> PR 工作流
        </button>
        <button :class="{ active: current === 'knowledge' }" @click="emit('navigate', 'knowledge')" :disabled="!activeProject">
          <span class="nav-ico" aria-hidden="true">▣</span> 知识库
        </button>
        <button :class="{ active: current === 'reviews' }" @click="emit('navigate', 'reviews')" :disabled="!activeProject">
          <span class="nav-ico" aria-hidden="true">✓</span> 审查
        </button>
        <button :class="{ active: current === 'agent' }" @click="emit('navigate', 'agent')" :disabled="!activeProject">
          <span class="nav-ico" aria-hidden="true">◆</span> Agent 审批
        </button>
        <button :class="{ active: current === 'aiLogs' }" @click="emit('navigate', 'aiLogs')" :disabled="!activeProject">
          <span class="nav-ico" aria-hidden="true">◷</span> AI 日志
        </button>
      </nav>

      <div class="sidebar-foot">
        <div class="user-chip">
          <div class="avatar">{{ (me.nickname || me.username || 'U').slice(0,1).toUpperCase() }}</div>
          <div>
            <div class="uname">{{ me.nickname || me.username }}</div>
            <div class="tagline">{{ me.role }}</div>
          </div>
        </div>
        <el-button @click="emit('logout')">退出登录</el-button>
      </div>
    </el-aside>

    <el-main class="content">
      <header class="topbar">
        <div class="crumb">
          <strong>{{ tabTitle }}</strong>
          <span v-if="activeProject" class="muted">/ {{ activeProject.name }} · 默认分支 <span class="mono">{{ activeProject.defaultBranch }}</span></span>
        </div>
        <div class="topbar-actions">
          <el-button :loading="busy.refresh" @click="emit('refresh')">刷新</el-button>
        </div>
      </header>

      <slot />
    </el-main>

    <!-- confirm modal:el-dialog 外壳,useConfirm 零改动(esc/遮罩关闭与
         原 @keyup.esc / @click.self 语义等价,close 事件统一走 dismiss) -->
    <el-dialog
      :model-value="!!confirmModal"
      :title="modalView.title"
      width="480px"
      align-center
      :show-close="false"
      @close="dismiss"
    >
      <p class="dialog-body">{{ modalView.body }}</p>
      <template #footer>
        <el-button @click="dismiss">取消</el-button>
        <el-button type="danger" :loading="busy.confirm" @click="run(confirmAction)">{{ modalView.confirmLabel || '确认删除' }}</el-button>
      </template>
    </el-dialog>

    <transition name="t"><div v-if="toast.text" class="toast" :class="toast.type" role="status">{{ toast.text }}</div></transition>
  </el-container>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useBusy } from '../composables/useBusy.js'
import { useConfirm } from '../composables/useConfirm.js'
import { useSession } from '../composables/useSession.js'
import { useToast } from '../composables/useToast.js'

const emit = defineEmits(['navigate', 'refresh', 'logout'])
const route = useRoute()
const { busy, run } = useBusy()
const { confirmModal, dismiss, confirmAction } = useConfirm()
const { me, activeProject } = useSession()
const { toast } = useToast()

const current = computed(() => (typeof route.name === 'string' ? route.name : 'dashboard'))
const tabTitles = { dashboard: '概览', projects: '项目管理', repository: '仓库配置', pullRequests: 'PR 工作流', knowledge: 'RAG 知识库', reviews: '代码审查', agent: 'Agent 审批', aiLogs: 'AI 调用日志' }
const tabTitle = computed(() => tabTitles[current.value] || 'RepoSage')

// 渐隐期间保留最后一次模态文案:dismiss 把 confirmModal 置空即触发
// el-dialog 关闭动效,若模板直读 confirmModal 会在渐隐中闪成空白。
const modalView = ref({})
watch(confirmModal, m => { if (m) modalView.value = m })
</script>

<style scoped>
/* Precision Workbench 亮色壳。布局走 Element 容器组件(el-container/
   el-aside/el-main),对容器的覆写只落在组件根(宽度/padding/overflow),
   不碰内部结构;导航保留原生 button 结构 tokens 重铸(role=button +
   无障碍名逐字不变,:disabled 原生语义,focus 环免费)。 */
.app-shell {
  min-height: 100vh;
  font-family: var(--rs-font-body);
  color: var(--rs-text);
  background: var(--rs-bg);
}

/* ---- 侧栏:亮色面 + 右描边,sticky 通高 ---- */
.sidebar {
  position: sticky;
  top: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
  gap: var(--sp-6);
  padding: var(--sp-6) var(--sp-5);
  background: var(--rs-surface);
  border-right: 1px solid var(--rs-border);
  overflow-y: auto;
}

.brand { display: flex; align-items: center; gap: var(--sp-3); }
.brand-logo {
  flex-shrink: 0;
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  background: var(--rs-primary);
  color: var(--rs-surface);
  border-radius: var(--rs-radius-base);
  font-size: var(--rs-fs-lg);
  font-weight: 800;
}
.brand h1 { margin: 0; font-family: var(--rs-font-body); font-size: var(--rs-fs-xl); font-weight: 800; letter-spacing: -0.01em; color: var(--rs-text); }
.tagline { margin-top: 3px; color: var(--rs-text-dim); font-size: var(--rs-fs-xs); font-family: var(--rs-font-mono); letter-spacing: 0.02em; }

/* ---- 主导航(原生 button,全属性自足,不依赖任何全局皮肤) ---- */
nav { display: grid; gap: var(--sp-1); }
nav button {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: flex-start;
  gap: var(--sp-3);
  min-height: 44px;
  padding: 0 18px;
  background: transparent;
  border: 0;
  border-radius: var(--rs-radius-sm);
  color: var(--rs-text-soft);
  font-family: var(--rs-font-body);
  font-size: var(--rs-fs-base);
  font-weight: 500;
  text-align: left;
  cursor: pointer;
  transition: background var(--rs-t-fast) var(--rs-ease-out), color var(--rs-t-fast) var(--rs-ease-out);
}
nav button:hover:not(:disabled) { background: var(--rs-fill); color: var(--rs-text); }
nav button:focus-visible { outline: 2px solid var(--rs-primary); outline-offset: -2px; }
nav button:disabled { opacity: 0.38; cursor: not-allowed; }
nav button.active {
  background: var(--el-color-primary-light-9);
  color: var(--rs-primary);
  font-weight: 600;
  box-shadow: inset 0 0 0 1px var(--el-color-primary-light-7);
}
nav button.active::before {
  content: "";
  position: absolute;
  left: -5px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 18px;
  border-radius: var(--rs-radius-round);
  background: var(--rs-primary);
}
.nav-ico { width: 20px; text-align: center; font-size: var(--rs-fs-md); opacity: 0.9; }

/* ---- 侧栏脚:用户 chip + 退出登录 ---- */
.sidebar-foot { margin-top: auto; display: grid; gap: var(--sp-3); }
.user-chip {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-2);
  background: var(--rs-fill-lighter);
  border: 1px solid var(--rs-border-faint);
  border-radius: var(--rs-radius-sm);
  color: var(--rs-text-soft);
  font-size: var(--rs-fs-sm);
}
.user-chip .uname { font-weight: 600; color: var(--rs-text); }
.avatar {
  flex-shrink: 0;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  background: var(--el-color-primary-light-8);
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: var(--rs-radius-base);
  color: var(--rs-primary);
  font-size: var(--rs-fs-sm);
  font-weight: 700;
}

/* ---- 内容区:el-main 默认 padding/overflow 在组件根覆写。
   overflow 必须回 visible:恢复 body 作为滚动容器,页内 sticky
   (AI 日志日期组头)才有可吸附的滚动祖先。 ---- */
.content {
  overflow: visible;
  width: 100%;
  max-width: 1240px;
  margin: 0 auto;
  padding: var(--sp-6) var(--sp-8);
}

.topbar { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-4); flex-wrap: wrap; margin-bottom: var(--sp-6); }
.crumb { display: flex; align-items: baseline; gap: var(--sp-3); flex-wrap: wrap; }
.crumb strong { font-family: var(--rs-font-body); font-size: var(--rs-fs-2xl); font-weight: 700; letter-spacing: -0.01em; color: var(--rs-text); }
.crumb .muted { color: var(--rs-text-dim); font-size: var(--rs-fs-sm); }
.crumb .muted .mono { color: var(--rs-text-soft); }
.topbar-actions { display: flex; align-items: center; gap: var(--sp-2); }

.dialog-body { margin: 0; color: var(--rs-text-soft); line-height: 1.6; }

/* ---- 响应式:窄屏侧栏降级为顶部横排(断点/行为与原版一致) ---- */
@media (max-width: 960px) {
  .app-shell { flex-direction: column; }
  .sidebar { position: relative; width: 100%; height: auto; border-right: 0; border-bottom: 1px solid var(--rs-border); }
  nav { grid-template-columns: repeat(3, 1fr); }
  nav button { justify-content: center; }
  nav button.active::before { display: none; }
  .content { padding: var(--sp-5) var(--sp-4); }
  .crumb strong { font-size: var(--rs-fs-xl); }
}

@media (prefers-reduced-motion: reduce) {
  nav button { transition: none; }
}
</style>
