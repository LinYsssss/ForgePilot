<template>
  <main class="app-shell">
    <aside class="sidebar">
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
        <button class="ghost" @click="emit('logout')">退出登录</button>
      </div>
    </aside>

    <section class="content">
      <header class="topbar">
        <div class="crumb">
          <strong>{{ tabTitle }}</strong>
          <span v-if="activeProject" class="muted">/ {{ activeProject.name }} · 默认分支 <span class="mono">{{ activeProject.defaultBranch }}</span></span>
        </div>
        <div class="topbar-actions">
          <button class="theme-toggle" :title="theme === 'dark' ? '切换到浅色模式' : '切换到深色模式'" :aria-label="theme === 'dark' ? '切换到浅色模式' : '切换到深色模式'" @click="toggleTheme">
            {{ theme === 'dark' ? '☀' : '☾' }}
          </button>
          <button class="secondary" @click="emit('refresh')" :disabled="busy.refresh">
            <span v-if="busy.refresh" class="spinner dark"></span>刷新
          </button>
        </div>
      </header>

      <slot />
    </section>

    <!-- confirm modal -->
    <transition name="t">
      <div v-if="confirmModal" class="modal-backdrop" @click.self="dismiss" @keyup.esc="dismiss">
        <div class="modal" role="dialog" aria-modal="true">
          <h3>{{ confirmModal.title }}</h3>
          <p>{{ confirmModal.body }}</p>
          <div class="actions">
            <button class="secondary" @click="dismiss">取消</button>
            <button class="danger solid" @click="run(confirmAction)" :disabled="busy.confirm">
              <span v-if="busy.confirm" class="spinner"></span>{{ confirmModal.confirmLabel || '确认删除' }}
            </button>
          </div>
        </div>
      </div>
    </transition>

    <transition name="t"><div v-if="toast.text" class="toast" :class="toast.type" role="status">{{ toast.text }}</div></transition>
  </main>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useBusy } from '../composables/useBusy'
import { useConfirm } from '../composables/useConfirm'
import { useSession } from '../composables/useSession'
import { useTheme } from '../composables/useTheme'
import { useToast } from '../composables/useToast'

const emit = defineEmits(['navigate', 'refresh', 'logout'])
const route = useRoute()
const { busy, run } = useBusy()
const { confirmModal, dismiss, confirmAction } = useConfirm()
const { me, activeProject } = useSession()
const { theme, toggleTheme } = useTheme()
const { toast } = useToast()

const current = computed(() => (typeof route.name === 'string' ? route.name : 'dashboard'))
const tabTitles = { dashboard: '概览', projects: '项目管理', repository: '仓库配置', pullRequests: 'PR 工作流', knowledge: 'RAG 知识库', reviews: '代码审查', agent: 'Agent 审批', aiLogs: 'AI 调用日志' }
const tabTitle = computed(() => tabTitles[current.value] || 'RepoSage')
</script>
