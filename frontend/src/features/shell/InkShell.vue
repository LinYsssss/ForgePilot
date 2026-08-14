<template>
  <div class="ink-stage ink-shell" :class="{ 'ink-static': staticMode, 'rail-collapsed': railCollapsed }">
    <InkAmbientScene variant="workspace" />
    <a class="ink-skip-link" href="#ink-workspace">跳到审查工作区</a>

    <header class="ink-topbar">
      <button
        ref="navToggleEl"
        class="ink-icon-button nav-toggle"
        aria-label="打开案卷导航"
        :aria-expanded="String(drawerState.open === 'nav')"
        aria-controls="ink-case-index"
        @click="openDrawer('nav')"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h16" /></svg>
      </button>

      <div class="ink-brand">
        <span class="ink-brand-mark" aria-hidden="true">睿</span>
        <span class="brand-text"><strong>RepoSage</strong><small>墨境审查院</small></span>
      </div>

      <div v-if="contextTitle" class="ink-context">
        <span>{{ contextLabel }}</span>
        <strong>{{ contextTitle }}</strong>
      </div>
      <div v-else class="ink-context" aria-hidden="true"></div>

      <div class="ink-top-actions">
        <slot name="top-actions" />
        <button class="ink-text-button" :aria-pressed="String(staticMode)" @click="toggleStaticMode()">
          {{ staticMode ? '启用水墨' : '静态墨境' }}
        </button>
        <button
          ref="railToggleEl"
          class="ink-icon-button rail-toggle"
          aria-label="朱批栏"
          :aria-expanded="String(railVisible)"
          aria-controls="ink-annotation-rail"
          @click="onRailToggle"
        >
          <span class="rail-toggle-glyph" aria-hidden="true">批</span>
        </button>
        <div v-if="user && user.name" class="ink-user">
          <span class="user-avatar" aria-hidden="true">{{ userInitial }}</span>
          <span class="user-meta"><strong>{{ user.name }}</strong><small>{{ user.role }}</small></span>
        </div>
        <button class="ink-text-button" @click="emit('logout')">退出登录</button>
      </div>
    </header>

    <CaseIndex
      id="ink-case-index"
      ref="caseIndexEl"
      :class="{ open: drawerState.open === 'nav' }"
      :items="navItems"
      :active-key="activeKey"
      :closable="drawerState.mobile"
      :inert="drawerState.navHidden"
      :aria-hidden="drawerState.navHidden ? 'true' : undefined"
      @navigate="onNavigate"
      @close="closeDrawer()"
    >
      <slot name="case" />
      <template #foot><slot name="index-foot" /></template>
    </CaseIndex>

    <main id="ink-workspace" class="ink-workspace" tabindex="-1">
      <slot />
    </main>

    <aside
      id="ink-annotation-rail"
      ref="railEl"
      class="ink-rail"
      :class="{ open: drawerState.open === 'rail' }"
      :inert="drawerState.railHidden"
      :aria-hidden="drawerState.railHidden ? 'true' : undefined"
      aria-label="朱批栏"
    >
      <div class="rail-head">
        <div><span class="ink-eyebrow">朱批</span><h2>{{ railTitle }}</h2></div>
        <button ref="railCloseEl" class="ink-icon-button" aria-label="关闭朱批栏" @click="onRailClose">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg>
        </button>
      </div>
      <div class="rail-body"><slot name="rail" /></div>
    </aside>

    <button v-if="drawerState.anyOpen" class="ink-scrim" type="button" aria-label="关闭当前侧栏" @click="closeDrawer()"></button>

    <transition name="t">
      <div v-if="toast.text" class="ink-toast" :class="toast.type" role="status">{{ toast.text }}</div>
    </transition>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import CaseIndex from './CaseIndex.vue'
import InkAmbientScene from '../../shared/motion/InkAmbientScene.vue'
import { useMotionPolicy } from '../../shared/motion/useMotionPolicy.js'
import { bindPointerField } from '../../shared/motion/usePointerField.js'
import { useToast } from '../../composables/useToast.js'
import {
  MOBILE_LAYOUT_QUERY,
  RAIL_DRAWER_QUERY,
  createDrawerController,
  nextTrapIndex,
} from './drawerController.js'

// 墨境壳层(合同 §4 布局 / §11 冻结抽屉合同):
//   ≥1280 案卷索引 236px / 主纸面 minmax(640px,1fr) / 朱批栏 292px(可折叠);
//   768–1279 保留案卷索引,朱批转抽屉;≤767 双抽屉 + 单卷任务流。
// 动效接线:唯一 pointer observer 与动效偏好监听都在这里绑定一次;
// 环境层(InkAmbientScene)可整体移除而不影响布局与功能。
const props = defineProps({
  navItems: { type: Array, default: () => [] },
  activeKey: { type: String, default: '' },
  contextLabel: { type: String, default: '当前案卷' },
  contextTitle: { type: String, default: '' },
  railTitle: { type: String, default: '审查批注' },
  user: { type: Object, default: () => ({}) },
})
const emit = defineEmits(['navigate', 'logout'])

const { toast } = useToast()
const { staticMode, toggleStaticMode, bindMotionPolicy } = useMotionPolicy()

const userInitial = computed(() => String(props.user?.name || 'U').slice(0, 1).toUpperCase())

/* ---------- 抽屉状态(纯逻辑在 drawerController,可测) ---------- */
const controller = createDrawerController()
const drawerState = ref(controller.snapshot())
const railCollapsed = ref(false)

const caseIndexEl = ref(null)
const railEl = ref(null)
const railCloseEl = ref(null)
const navToggleEl = ref(null)
const railToggleEl = ref(null)

const railVisible = computed(() =>
  drawerState.value.railDrawer ? drawerState.value.open === 'rail' : !railCollapsed.value
)

function commit() {
  drawerState.value = controller.snapshot()
}

function focusTarget(target) {
  if (target === 'nav-close') caseIndexEl.value?.focusClose()
  else if (target === 'rail-close') railCloseEl.value?.focus()
  else if (target === 'nav-toggle') navToggleEl.value?.focus()
  else if (target === 'rail-toggle') railToggleEl.value?.focus()
}

async function openDrawer(type) {
  const target = controller.openDrawer(type)
  commit()
  await nextTick()
  focusTarget(target)
}

async function closeDrawer(returnFocus = true) {
  const target = controller.closeDrawer()
  commit()
  if (returnFocus && target) {
    await nextTick()
    focusTarget(target)
  }
}

function onRailToggle() {
  if (drawerState.value.railDrawer) {
    if (drawerState.value.open === 'rail') closeDrawer()
    else openDrawer('rail')
    return
  }
  railCollapsed.value = !railCollapsed.value
}

async function onRailClose() {
  if (drawerState.value.railDrawer) return closeDrawer()
  // 桌面折叠:关闭按钮随栏消失,焦点交还顶栏开关,键盘路径不断
  railCollapsed.value = true
  await nextTick()
  railToggleEl.value?.focus()
}

function onNavigate(key) {
  if (drawerState.value.open === 'nav') closeDrawer(false)
  emit('navigate', key)
}

/* ---------- 布局监听与键盘合同 ---------- */
let mobileQuery = null
let railQuery = null

function syncLayout() {
  controller.setLayout({ mobile: mobileQuery.matches, railDrawer: railQuery.matches })
  commit()
}

function onKeydown(event) {
  if (event.key === 'Escape') {
    if (controller.escapeTarget()) closeDrawer()
    return
  }
  if (event.key !== 'Tab') return
  const snap = controller.snapshot()
  if (!snap.open) return
  const root = snap.open === 'nav' ? caseIndexEl.value?.$el : railEl.value
  if (!root) return
  const focusables = [...root.querySelectorAll('button:not(:disabled), input:not(:disabled), a[href], [tabindex]:not([tabindex="-1"])')]
    .filter((el) => !el.hidden)
  const next = nextTrapIndex(focusables.length, focusables.indexOf(document.activeElement), event.shiftKey)
  if (next !== null) {
    event.preventDefault()
    focusables[next]?.focus()
  }
}

watch(() => drawerState.value.anyOpen, (open) => {
  document.body.classList.toggle('ink-drawer-open', open)
})

let unbindMotion = null
let unbindPointer = null

onMounted(() => {
  unbindMotion = bindMotionPolicy()
  unbindPointer = bindPointerField()
  mobileQuery = window.matchMedia(MOBILE_LAYOUT_QUERY)
  railQuery = window.matchMedia(RAIL_DRAWER_QUERY)
  mobileQuery.addEventListener?.('change', syncLayout)
  railQuery.addEventListener?.('change', syncLayout)
  syncLayout()
  document.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  mobileQuery?.removeEventListener?.('change', syncLayout)
  railQuery?.removeEventListener?.('change', syncLayout)
  document.removeEventListener('keydown', onKeydown)
  document.body.classList.remove('ink-drawer-open')
  unbindMotion?.()
  unbindPointer?.()
})
</script>

<style scoped>
.ink-shell {
  display: grid;
  grid-template:
    "top top top" var(--ink-topbar-h)
    "index work rail" minmax(calc(100vh - var(--ink-topbar-h)), auto)
    / var(--ink-index-w) minmax(640px, 1fr) var(--ink-rail-w);
  overflow: clip;
}
.ink-shell.rail-collapsed {
  grid-template:
    "top top" var(--ink-topbar-h)
    "index work" minmax(calc(100vh - var(--ink-topbar-h)), auto)
    / var(--ink-index-w) minmax(640px, 1fr);
}
.ink-shell.rail-collapsed .ink-rail { display: none; }

/* ---- 顶栏:全局上下文 + 动效控制 + 用户入口(合同 §4) ---- */
.ink-topbar {
  grid-area: top;
  z-index: 20;
  display: flex;
  align-items: center;
  min-width: 0;
  height: var(--ink-topbar-h);
  border-bottom: 1px solid var(--ink-border-topbar);
  background: var(--ink-wash-topbar);
  backdrop-filter: blur(12px);
  box-shadow: var(--ink-shadow-topbar);
}
.nav-toggle { display: none; margin-left: var(--ink-sp-8); }

.ink-brand {
  display: flex;
  align-items: center;
  gap: var(--ink-sp-12);
  flex: 0 0 auto;
  width: var(--ink-index-w);
  height: 100%;
  padding: 0 var(--ink-sp-16);
  border-right: 1px solid var(--line-soft);
  color: var(--ink-strong);
}
.ink-brand-mark {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  color: var(--cinnabar);
  border: 2px solid currentColor;
  border-radius: var(--ink-radius-paper);
  font-family: var(--ink-font-display);
  font-size: var(--ink-fs-16);
  font-weight: 700;
  transform: rotate(-2deg);
}
.brand-text strong { display: block; font-family: var(--ink-font-display); font-size: var(--ink-fs-16); letter-spacing: 0.02em; }
.brand-text small { display: block; margin-top: 1px; color: var(--ink-muted); font-size: var(--ink-fs-12); letter-spacing: 0.16em; }

.ink-context { flex: 1 1 auto; min-width: 0; padding: 0 var(--ink-sp-24); }
.ink-context span { display: block; color: var(--ink-muted); font-size: var(--ink-fs-12); letter-spacing: 0.12em; }
.ink-context strong {
  display: block;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink-strong);
  font-size: var(--ink-fs-14);
}

.ink-top-actions {
  display: flex;
  align-items: center;
  gap: var(--ink-sp-8);
  margin-left: auto;
  padding-right: var(--ink-sp-16);
}
.rail-toggle-glyph { font-family: var(--ink-font-display); font-size: var(--ink-fs-14); }

.ink-user { display: flex; align-items: center; gap: var(--ink-sp-8); }
.user-avatar {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  color: var(--ink-on-accent);
  background: var(--ink-default);
  border-radius: 50%;
  font-size: var(--ink-fs-13);
  font-weight: 700;
}
.user-meta strong { display: block; color: var(--ink-strong); font-size: var(--ink-fs-13); line-height: 1.2; }
.user-meta small { display: block; color: var(--ink-muted); font-size: var(--ink-fs-12); }

/* ---- 案卷索引列(桌面/平板为常规列) ---- */
.ink-shell .ink-case-index { grid-area: index; z-index: 10; min-height: calc(100vh - var(--ink-topbar-h)); }

/* ---- 主纸面:最高视觉优先级的稳定信息层 ---- */
.ink-workspace {
  grid-area: work;
  z-index: 2;
  min-width: 0;
  padding: 28px clamp(20px, 3vw, 42px) 64px;
  background: var(--ink-wash-workspace);
  box-shadow: inset 0 0 80px rgb(255 255 255 / 0.42);
}

/* ---- 朱批栏 ---- */
.ink-rail {
  grid-area: rail;
  z-index: 8;
  min-height: calc(100vh - var(--ink-topbar-h));
  padding: var(--ink-sp-24) var(--ink-sp-16);
  border-left: 1px solid var(--line-soft);
  background: var(--ink-wash-rail);
  backdrop-filter: blur(8px);
}
.rail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--ink-sp-12); }
.rail-head h2 { margin: 5px 0 0; font-family: var(--ink-font-display); font-size: var(--ink-fs-16); }
.rail-body { margin-top: var(--ink-sp-16); }

.ink-scrim {
  position: fixed;
  inset: var(--ink-topbar-h) 0 0;
  z-index: 48;
  border: 0;
  padding: 0;
  background: var(--ink-wash-scrim);
  cursor: pointer;
}

/* ---- 平板 768–1279:保留案卷索引,朱批转抽屉(冻结断点) ---- */
@media (max-width: 1279px) {
  .ink-shell,
  .ink-shell.rail-collapsed {
    grid-template:
      "top top" var(--ink-topbar-h)
      "index work" minmax(calc(100vh - var(--ink-topbar-h)), auto)
      / var(--ink-index-w-tablet) minmax(0, 1fr);
  }
  .ink-brand { width: var(--ink-index-w-tablet); }
  .ink-shell .ink-rail,
  .ink-shell.rail-collapsed .ink-rail {
    display: block;
    position: fixed;
    z-index: 50;
    top: var(--ink-topbar-h);
    right: 0;
    bottom: 0;
    width: min(330px, 88vw);
    min-height: 0;
    overflow: auto;
    transform: translateX(102%);
    transition: transform var(--ink-t-drawer) var(--ink-ease-out);
    box-shadow: var(--ink-shadow-drawer);
  }
  .ink-shell .ink-rail.open,
  .ink-shell.rail-collapsed .ink-rail.open { transform: translateX(0); }
}

/* ---- 手机 ≤767:双抽屉 + 单卷任务流(冻结断点;朱批入口在 sticky 顶栏) ---- */
@media (max-width: 767px) {
  .ink-shell,
  .ink-shell.rail-collapsed {
    grid-template:
      "top" var(--ink-topbar-h-mobile)
      "work" auto
      / minmax(0, 1fr);
  }
  .ink-topbar { position: sticky; top: 0; height: var(--ink-topbar-h-mobile); }
  .nav-toggle { display: grid; }
  .ink-brand { width: auto; padding: 0 var(--ink-sp-8); border-right: 0; }
  .ink-context { display: none; }
  .ink-top-actions { padding-right: var(--ink-sp-8); }
  .ink-shell .ink-case-index {
    position: fixed;
    z-index: 55;
    top: 0;
    left: 0;
    bottom: 0;
    width: min(290px, 88vw);
    min-height: 0;
    overflow: auto;
    transform: translateX(-102%);
    transition: transform var(--ink-t-drawer) var(--ink-ease-out);
    box-shadow: var(--ink-shadow-drawer);
  }
  .ink-shell .ink-case-index.open { transform: translateX(0); }
  .ink-shell .ink-rail,
  .ink-shell.rail-collapsed .ink-rail { top: var(--ink-topbar-h-mobile); z-index: 54; }
  .ink-scrim { inset: 0; z-index: 52; }
  .ink-workspace { padding: 22px clamp(16px, 4vw, 28px) 80px; }
}

@media (max-width: 560px) {
  .ink-user { display: none; }
  .ink-workspace { padding-inline: var(--ink-sp-12); }
  .brand-text strong { font-size: var(--ink-fs-14); }
}
</style>
