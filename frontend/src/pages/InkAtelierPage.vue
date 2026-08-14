<template>
  <LoginGate v-if="!authenticated" @authenticated="onAuthenticated" />

  <InkShell
    v-else
    :nav-items="navItems"
    active-key="atelier"
    context-label="当前项目"
    :context-title="activeProject ? activeProject.name : '未选择项目'"
    :user="{ name: me.nickname || me.username, role: me.role }"
    @navigate="onNavigate"
    @logout="logout()"
  >
    <template #case>
      <section class="case-card" aria-labelledby="ink-case-card-title">
        <div class="case-card-label"><span id="ink-case-card-title">当前案卷</span><b>骨架</b></div>
        <h2>墨境书院 · 生产骨架</h2>
        <dl>
          <div><dt>阶段</dt><dd>步骤 5 / 隔离入口</dd></div>
          <div><dt>合同</dt><dd>ui-design v1.0</dd></div>
        </dl>
      </section>
    </template>
    <template #index-foot>
      <span>书院守门规则</span>
      <strong>UI 合同 v1.0 · 已冻结</strong>
    </template>

    <section class="ink-panel workspace-head ink-reveal">
      <div>
        <span class="ink-eyebrow">生产骨架 · 隔离入口</span>
        <h1>墨境书院审查台</h1>
        <p>
          新壳层、令牌、动效与门禁已按冻结合同就位。Agent 审查与 Reviews
          证据主路径将在下一切片迁入本纸面;当前旧页面仍由左侧案卷索引直达。
        </p>
      </div>
      <div class="head-actions">
        <button class="ink-outline-button" :disabled="busy.refresh" @click="run(refreshAll, 'refresh')">
          {{ busy.refresh ? '正在刷新…' : '刷新会话数据' }}
        </button>
        <button class="ink-primary-button" @click="playStamp">演示落印反馈</button>
      </div>
    </section>

    <section class="ink-panel ink-reveal" aria-labelledby="ink-seal-demo-title">
      <div class="section-heading">
        <div>
          <span class="ink-eyebrow">印记原语</span>
          <h2 id="ink-seal-demo-title">SealBadge 状态样例</h2>
        </div>
        <span class="section-note">双编码:印记形状 + 印记字 + 文本;朱砂仅属 Critical</span>
      </div>
      <div class="seal-row">
        <SealBadge tone="critical" label="Critical 示例" :stamp="stampTick" />
        <SealBadge tone="high" label="High 示例" />
        <SealBadge tone="medium" label="Medium 示例" />
        <SealBadge tone="low" label="Low 示例" />
        <SealBadge tone="running" label="运行中示例" />
        <SealBadge tone="success" label="已通过示例" />
      </div>
    </section>

    <section class="ink-panel ink-reveal" aria-labelledby="ink-brush-demo-title">
      <div class="section-heading">
        <div>
          <span class="ink-eyebrow">笔触原语</span>
          <h2 id="ink-brush-demo-title">BrushProgress 六步样例</h2>
        </div>
        <span class="section-note">{{ demoCurrent }} / {{ demoSteps.length }} 已推进 · 描线仅在阶段变化时过渡一次</span>
      </div>
      <BrushProgress :steps="demoSteps" :current="demoCurrent" />
      <div class="demo-actions">
        <button class="ink-outline-button" :disabled="demoCurrent <= 0" @click="demoCurrent -= 1">回退一步</button>
        <button class="ink-outline-button" :disabled="demoCurrent >= demoSteps.length" @click="demoCurrent += 1">推进一步</button>
      </div>
    </section>

    <section class="ink-panel ink-reveal" aria-labelledby="ink-skeleton-note-title">
      <div class="section-heading">
        <div>
          <span class="ink-eyebrow">迁移路线</span>
          <h2 id="ink-skeleton-note-title">此入口的边界</h2>
        </div>
      </div>
      <ul class="note-list">
        <li>本页是隔离骨架入口:令牌、三层渲染面、抽屉与动效降级在此验证,不承载业务结论。</li>
        <li>全部既有页面(总览/项目/仓库/PR/审查/Agent/知识库/AI 日志)保持旧壳层可达可用。</li>
        <li>下一切片将把 Agent → Finding → 证据/Diff → 审批主路径迁入本纸面,并接入真实数据。</li>
      </ul>
    </section>

    <template #rail>
      <ol class="rail-notes">
        <li class="is-critical">
          <time>骨架</time>
          <strong>朱批栏行为已冻结</strong>
          <p>桌面为常驻列可折叠;平板/手机转抽屉,关闭态移出键盘序,打开态焦点入栏。</p>
        </li>
        <li>
          <time>骨架</time>
          <strong>批注内容待接入</strong>
          <p>Agent 批注、来源与定位锚点在下一切片随真实数据迁入。</p>
        </li>
      </ol>
      <blockquote class="rail-quote">"所有阻断结论，必须能回到持久化证据。"<cite>— RepoSage 守门规范</cite></blockquote>
    </template>
  </InkShell>
</template>

<script setup>
import { computed, ref } from 'vue'
import LoginGate from '../features/auth/LoginGate.vue'
import InkShell from '../features/shell/InkShell.vue'
import SealBadge from '../shared/ui/SealBadge.vue'
import BrushProgress from '../shared/ui/BrushProgress.vue'
import { useSession } from '../composables/useSession.js'
import { useBusy } from '../composables/useBusy.js'
import { useToast } from '../composables/useToast.js'
import { useWorkspace } from '../composables/useWorkspace.js'

// 墨境隔离入口(implement.md 步骤 5 / design.md §8.1):
// 门禁分流沿用 App.vue 的登录态语义 —— 未认证渲染 LoginGate,认证后
// 渲染新壳层;认证、401 漏斗、CSRF 引导均复用既有单例,不建第二套。
// 页面只做路由编排:业务导航映射与 App.vue onNavigate 一致,
// 保证从墨境入口出发,旧页面的装载行为不变。
const { authenticated, me, activeProject } = useSession()
const { busy, run } = useBusy()
const { toastMsg } = useToast()
const { goto, goTab, loadMe, refreshAll, logout, openAgentWorkspace, openProjectAiLogs } = useWorkspace()

const navItems = computed(() => [
  { key: 'atelier', glyph: '墨', label: '墨境工作台' },
  { key: 'dashboard', glyph: '概', label: '总览' },
  { key: 'projects', glyph: '项', label: '项目' },
  { key: 'repository', glyph: '仓', label: '仓库', disabled: !activeProject.value },
  { key: 'pullRequests', glyph: '合', label: 'PR 工作流', disabled: !activeProject.value },
  { key: 'reviews', glyph: '审', label: '审查记录', disabled: !activeProject.value },
  { key: 'agent', glyph: '巡', label: 'Agent 审批', disabled: !activeProject.value },
  { key: 'knowledge', glyph: '知', label: '知识库', disabled: !activeProject.value },
  { key: 'aiLogs', glyph: '录', label: 'AI 日志', disabled: !activeProject.value },
])

function onNavigate(name) {
  if (name === 'atelier') return
  if (name === 'agent') return openAgentWorkspace()
  if (name === 'aiLogs') return openProjectAiLogs()
  if (name === 'dashboard' || name === 'projects') return goto(name)
  goTab(name)
}

// 登录成功:与 afterLogin 同源动作(loadMe + refreshAll),但停留在墨境入口
async function onAuthenticated() {
  await run(async () => {
    await loadMe()
    await refreshAll()
    toastMsg('登录成功', 'success')
  }, 'refresh')
}

/* ---------- 原语演示(仅示例态,不接业务数据) ---------- */
const demoSteps = [
  { key: 'intake', label: '接卷', glyph: '接', meta: '示例' },
  { key: 'index', label: '索引', glyph: '索', meta: '示例' },
  { key: 'static', label: '静态分析', glyph: '析', meta: '示例' },
  { key: 'security', label: '安全分析', glyph: '安', meta: '示例' },
  { key: 'review', label: '复核', glyph: '核', meta: '示例' },
  { key: 'report', label: '报告', glyph: '报', meta: '示例' },
]
const demoCurrent = ref(3)

const stampTick = ref(false)
function playStamp() {
  stampTick.value = false
  requestAnimationFrame(() => {
    stampTick.value = true
  })
}
</script>

<style scoped>
.ink-panel {
  margin-top: var(--ink-sp-16);
  padding: var(--ink-sp-24);
  background: var(--ink-wash-panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--ink-radius-paper);
  box-shadow: var(--ink-shadow-panel);
}

.workspace-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ink-sp-24);
  margin-top: 0;
}
.workspace-head h1 {
  margin: 6px 0;
  font-family: var(--ink-font-display);
  font-size: clamp(24px, 2.2vw, var(--ink-fs-28));
  letter-spacing: 0.03em;
}
.workspace-head p { max-width: 60ch; margin: 0; color: var(--ink-muted); font-size: var(--ink-fs-13); line-height: 1.7; }
.head-actions { display: flex; flex-wrap: wrap; gap: var(--ink-sp-8); }

.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--ink-sp-16);
  margin-bottom: var(--ink-sp-16);
}
.section-heading h2 { margin: 5px 0 0; font-family: var(--ink-font-display); font-size: var(--ink-fs-16); letter-spacing: 0.02em; }
.section-note { color: var(--ink-muted); font-size: var(--ink-fs-12); }

.seal-row { display: flex; flex-wrap: wrap; gap: var(--ink-sp-16) var(--ink-sp-24); }

.demo-actions { display: flex; gap: var(--ink-sp-8); margin-top: var(--ink-sp-16); }

.note-list { margin: 0; padding-left: 1.2em; display: grid; gap: var(--ink-sp-8); color: var(--ink-default); font-size: var(--ink-fs-13); line-height: 1.7; }

/* ---- 案卷卡(CaseIndex slot) ---- */
.case-card {
  padding: var(--ink-sp-16);
  background: var(--ink-wash-panel-faint);
  border: 1px solid var(--line-soft);
  border-left: 3px solid var(--cinnabar);
}
.case-card-label { display: flex; align-items: center; justify-content: space-between; gap: var(--ink-sp-12); color: var(--ink-muted); font-size: var(--ink-fs-12); }
.case-card-label b { color: var(--mineral-cyan); font-size: var(--ink-fs-12); }
.case-card h2 { margin: var(--ink-sp-8) 0 var(--ink-sp-12); font-family: var(--ink-font-display); font-size: var(--ink-fs-15); }
.case-card dl { margin: 0; }
.case-card dl div {
  display: flex;
  justify-content: space-between;
  gap: var(--ink-sp-12);
  padding: 5px 0;
  border-top: 1px solid var(--line-soft);
  font-size: var(--ink-fs-12);
}
.case-card dt { color: var(--ink-muted); }
.case-card dd { margin: 0; color: var(--ink-strong); }

/* ---- 朱批栏占位(rail slot) ---- */
.rail-notes { position: relative; margin: 0; padding: 0 0 0 19px; list-style: none; }
.rail-notes::before {
  content: "";
  position: absolute;
  top: 8px;
  bottom: 10px;
  left: 4px;
  width: 1px;
  background: var(--line-strong);
}
.rail-notes li {
  position: relative;
  margin-bottom: var(--ink-sp-16);
  padding: var(--ink-sp-12);
  background: var(--ink-wash-panel-faint);
  border: 1px solid var(--line-soft);
}
.rail-notes li::before {
  content: "";
  position: absolute;
  top: 15px;
  left: -19px;
  width: 8px;
  height: 8px;
  background: var(--line-strong);
  border: 3px solid var(--surface-muted);
  border-radius: 50%;
}
.rail-notes li.is-critical { border-left: 2px solid var(--cinnabar); }
.rail-notes li.is-critical::before { background: var(--cinnabar); }
.rail-notes time { display: block; color: var(--ink-muted); font-size: var(--ink-fs-12); }
.rail-notes strong { display: block; margin-top: 5px; color: var(--ink-strong); font-size: var(--ink-fs-13); }
.rail-notes p { margin: 5px 0 0; color: var(--ink-default); font-size: var(--ink-fs-12); line-height: 1.55; }

.rail-quote {
  margin: var(--ink-sp-24) 2px 0;
  padding: var(--ink-sp-12) 0 var(--ink-sp-12) var(--ink-sp-16);
  color: var(--ink-muted);
  border-left: 2px solid var(--cinnabar);
  font-family: var(--ink-font-display);
  font-size: var(--ink-fs-13);
  line-height: 1.7;
}
.rail-quote cite { display: block; margin-top: 5px; font-family: var(--ink-font-body); font-size: var(--ink-fs-12); font-style: normal; }

@media (max-width: 880px) {
  .workspace-head { display: grid; }
  .head-actions { width: 100%; }
}
@media (max-width: 560px) {
  .ink-panel { padding: var(--ink-sp-16) var(--ink-sp-12); }
  .section-heading { display: grid; align-items: start; }
  .head-actions { display: grid; grid-template-columns: 1fr 1fr; }
}
</style>
