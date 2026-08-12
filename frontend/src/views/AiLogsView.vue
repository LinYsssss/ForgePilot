<template>
  <div class="view">
      <div class="rs-panel">
        <div class="card-head">
          <div><h2>AI 调用日志</h2><div class="card-sub">{{ aiLogScope }} · 本页 {{ aiLogs.length }} 条，按日期归类</div></div>
          <div class="head-actions">
            <template v-if="aiLogTotalPages > 1">
              <el-button :disabled="aiLogPage === 0" @click="run(prevAiLogPage)">← 上一页</el-button>
              <span class="muted mono">{{ aiLogPage + 1 }}/{{ aiLogTotalPages }}</span>
              <el-button :disabled="aiLogPage + 1 >= aiLogTotalPages" @click="run(nextAiLogPage)">下一页 →</el-button>
            </template>
            <el-button @click="run(openProjectAiLogs)">刷新项目日志</el-button>
          </div>
        </div>
        <el-empty v-if="!aiLogs.length" description="暂无调用日志"><p class="empty-extra">执行一次审查或检索后会生成。</p></el-empty>
        <div v-else>
          <div v-for="g in groupedAiLogs" :key="g.date" class="log-group">
            <button class="log-group-head" :class="{ collapsed: collapsedDates[g.date] }" @click="toggleDate(g.date)">
              <span class="caret" aria-hidden="true">▾</span>
              <span class="date">{{ g.date }} <span class="rel">{{ g.relative }}</span></span>
              <span class="divider"></span>
              <span class="count">{{ g.items.length }} 次调用</span>
            </button>
            <div v-show="!collapsedDates[g.date]" class="log-group-body">
              <div v-for="tg in g.taskGroups" :key="tg.key" class="log-task-group">
                <div class="log-task-label">
                  <span v-if="tg.taskId">🧪 任务 <span class="mono">#{{ tg.taskId }}</span></span>
                  <span v-else>🔎 项目级调用（检索 / 向量）</span>
                  <span class="grow"></span>
                  <span class="count">{{ tg.items.length }}</span>
                </div>
                <div class="list" v-list-nav>
                  <button v-for="l in tg.items" :key="l.id" class="list-row row-ailog" :class="{ selected: selectedAiLog && selectedAiLog.id === l.id }" @click="selectedAiLog = l">
                    <span class="badge plain">{{ l.requestType }}</span>
                    <span class="grow mono hide-sm">{{ l.provider }} / {{ l.model }}</span>
                    <span class="status-pill" :class="'st-' + l.status">{{ l.status }}</span>
                    <span class="mono hide-sm">{{ l.latencyMs }}ms</span>
                    <span class="mono hide-sm">{{ l.promptChars }}→{{ l.responseChars }}</span>
                    <span v-if="l.totalTokens" class="mono hide-sm">{{ l.totalTokens }} tok</span>
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div class="rs-panel" v-if="selectedAiLog">
        <div class="card-head"><div><h2>调用详情 #{{ selectedAiLog.id }}</h2><div class="card-sub">{{ fmtTime(selectedAiLog.createdAt) }}</div></div><el-button @click="selectedAiLog = null">收起</el-button></div>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="类型">{{ selectedAiLog.requestType }}</el-descriptions-item>
          <el-descriptions-item label="Provider">{{ selectedAiLog.provider }}</el-descriptions-item>
          <el-descriptions-item label="模型">{{ selectedAiLog.model }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ selectedAiLog.latencyMs }} ms</el-descriptions-item>
          <el-descriptions-item label="输入">{{ selectedAiLog.promptChars }} 字符</el-descriptions-item>
          <el-descriptions-item label="输出">{{ selectedAiLog.responseChars }} 字符</el-descriptions-item>
          <el-descriptions-item label="Token">{{ selectedAiLog.promptTokens }} 入 / {{ selectedAiLog.completionTokens }} 出 / {{ selectedAiLog.totalTokens }} 总</el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="selectedAiLog.errorMessage" class="err-alert" type="error" :closable="false" :title="selectedAiLog.errorMessage" />
      </div>
  </div>
</template>

<script setup>
import { fmtTime } from '../utils/format.js'
import { useBusy } from '../composables/useBusy.js'
import { useAiLogs } from '../composables/useAiLogs.js'
import { useWorkspace } from '../composables/useWorkspace.js'

const { busy, run } = useBusy()
const { aiLogs, aiLogScope, selectedAiLog, collapsedDates, groupedAiLogs, aiLogPage, aiLogTotalPages, nextAiLogPage, prevAiLogPage, toggleDate } = useAiLogs()
const { openProjectAiLogs } = useWorkspace()
</script>

<style scoped>
/* Precision Workbench 版式基线(并行迁移期 AppShell 尚未迁移,基线挂本视图根;
   收尾后维持原状,与全局底座无冲突)。
   面板外壳手铸而非 el-card:本页交互核心是 sticky 日期组头,el-card 的
   .el-card__body 自带 overflow:auto,会把 sticky 的滚动容器截在卡体内
   (页面滚动时组头永不吸附,交互静默失效);修正内部结构需 :deep(),为规约所禁。
   与 LoginView「卡片手铸」同一先例:布局自足,交互组件全交 Element。 */
.view { font-family: var(--rs-font-body); color: var(--rs-text); }

.rs-panel {
  background: var(--rs-surface);
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-base);
  box-shadow: var(--rs-shadow-sm);
  padding: var(--sp-6);
  margin-bottom: var(--sp-5);
}

.card-head { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); flex-wrap: wrap; margin-bottom: var(--sp-5); }
.card-head h2 { margin: 0; font-family: var(--rs-font-body); font-size: var(--rs-fs-lg); font-weight: 700; color: var(--rs-text); }
.card-sub { margin-top: 3px; color: var(--rs-text-dim); font-size: var(--rs-fs-sm); font-weight: 400; }
.head-actions { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }

.muted { color: var(--rs-text-dim); font-size: var(--rs-fs-sm); }
.mono { font-family: var(--rs-font-mono); font-feature-settings: "liga" 0; }

.empty-extra { margin: 0; color: var(--rs-text-dim); font-size: var(--rs-fs-sm); }

/* ---- 日期分组折叠(本页交互核心,保留自定义骨架用 tokens 重铸:
   el-collapse 需把 collapsedDates 反演成 activeNames 数组(消费方式变形),
   且组头的 sticky/分隔线/计数布局无法等价,保真度取胜)。
   collapsed 状态类与 v-show 展开语义与原版逐点一致。 ---- */
.log-group { margin-bottom: var(--sp-4); }
.log-group:last-child { margin-bottom: 0; }

.log-group-head {
  position: sticky; top: 0; z-index: var(--rs-z-sticky);
  display: flex; align-items: center; justify-content: flex-start; gap: var(--sp-3);
  width: 100%; min-height: 0; padding: var(--sp-2) 0;
  /* 原版透明底在亮色下吸附时会与滚动行叠字,垫上卡面色;吸附行为本身不变 */
  background: var(--rs-surface);
  border: 0; border-radius: var(--rs-radius-sm);
  box-shadow: none; color: var(--rs-text);
  font-family: var(--rs-font-body); font-size: var(--rs-fs-base); font-weight: 600; letter-spacing: normal;
  text-align: left; cursor: pointer; transition: none;
}
.log-group-head:hover:not(:disabled) { background: var(--rs-surface); transform: none; box-shadow: none; }
.log-group-head:active:not(:disabled) { transform: none; }
.log-group-head:focus-visible { outline: 2px solid var(--rs-primary); outline-offset: -2px; box-shadow: none; }
.log-group-head .caret { width: 14px; color: var(--rs-text-dim); font-size: var(--rs-fs-sm); transition: transform var(--rs-t-fast) var(--rs-ease-out); }
.log-group-head.collapsed .caret { transform: rotate(-90deg); }
.log-group-head .date { display: inline-flex; align-items: baseline; gap: var(--sp-2); font-family: var(--rs-font-body); font-size: var(--rs-fs-base); font-weight: 700; color: var(--rs-text); }
.log-group-head .date .rel { font-family: var(--rs-font-mono); font-size: var(--rs-fs-xs); font-weight: 500; color: var(--rs-text-dim); }
.log-group-head .divider { flex: 1; height: 1px; background: var(--rs-border); }
.log-group-head .count { color: var(--rs-text-dim); font-size: var(--rs-fs-xs); font-family: var(--rs-font-mono); }

.log-group-body { display: grid; gap: var(--sp-2); padding-top: var(--sp-2); }

/* ---- 任务子分组 ---- */
.log-task-group { overflow: hidden; border: 1px solid var(--rs-border-faint); border-radius: var(--rs-radius-sm); background: var(--rs-surface); }
.log-task-label {
  display: flex; align-items: center; gap: var(--sp-2); padding: 7px 12px;
  background: var(--rs-fill-lighter); border-bottom: 1px solid var(--rs-border-faint);
  color: var(--rs-text-dim); font-size: var(--rs-fs-xs); font-weight: 600;
}
.log-task-label .mono { color: var(--rs-text-soft); }

/* ---- 日志行:保留原生 button + .list-row 类(v-list-nav 的 roving focus
   依赖该类查询;el-table 会拆掉键盘导航与选中语义,故走 tokens 网格)。
   Observatory 元素级 button 皮肤迁移期仍全局生效,泄漏属性逐项定值;
   选择器保持 .log-task-group .list-row 形态压过全局同形规则。 ---- */
.log-task-group .list { display: grid; gap: 0; }
.log-task-group .list-row {
  display: grid; align-items: center; gap: var(--sp-3);
  width: 100%; min-height: 0; padding: 11px 14px;
  background: var(--rs-surface);
  border: 0; border-bottom: 1px solid var(--rs-border-faint); border-radius: 0;
  box-shadow: none;
  color: var(--rs-text); font-family: var(--rs-font-body); font-size: var(--rs-fs-base); font-weight: 500; letter-spacing: normal;
  text-align: left; cursor: pointer;
  transition: background var(--rs-t-fast) var(--rs-ease-out), box-shadow var(--rs-t-fast);
}
.log-task-group .list-row:last-child { border-bottom: 0; }
.log-task-group .list-row:hover:not(:disabled) { background: var(--rs-fill-lighter); border-color: var(--rs-border-faint); transform: none; box-shadow: none; }
.log-task-group .list-row:active:not(:disabled) { transform: translateY(1px); }
.log-task-group .list-row:focus-visible { outline: 2px solid var(--rs-primary); outline-offset: -2px; box-shadow: none; }
.log-task-group .list-row.selected { background: var(--el-color-primary-light-9); border-color: var(--rs-border-faint); box-shadow: inset 3px 0 0 var(--rs-primary); }
.list-row .mono { color: var(--rs-text-soft); font-size: var(--rs-fs-sm); }
.list-row .grow { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 五列:类型徽标 / provider·model / 状态 / 耗时 / 字符量(+token 溢出至隐式行,与原版一致) */
.row-ailog { grid-template-columns: 132px 1fr 88px 78px 110px; }

/* ---- 类型徽标(badge plain 同名重定义) ---- */
.badge {
  display: inline-flex; align-items: center; gap: var(--sp-1);
  padding: 3px 10px; border-radius: var(--rs-radius-round);
  font-size: var(--rs-fs-xs); font-weight: 600; letter-spacing: 0.02em;
  line-height: 1.5; white-space: nowrap;
}
.badge.plain { background: var(--rs-fill); color: var(--rs-text-soft); font-family: var(--rs-font-mono); font-weight: 600; }
.badge.plain::before { content: none; display: none; }

/* ---- 状态 pill(st-* 同名重定义;分组即 design-tokens.md 全站唯一映射,
   AI 日志现值为 SUCCESS/FAILED,整族收录保持全站一致与前向稳健) ---- */
.status-pill {
  display: inline-flex; align-items: center; gap: var(--sp-2);
  padding: 4px 11px; border: 1px solid transparent; border-radius: var(--rs-radius-round);
  font-size: var(--rs-fs-xs); font-weight: 700; white-space: nowrap;
}
.status-pill::before { content: ""; width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.st-SUCCESS, .st-INDEXED, .st-ACTIVE, .st-CONSUMED, .st-PUBLISHED, .st-PASSED, .st-OPEN, .st-MERGED {
  color: var(--rs-risk-low-text); background: var(--rs-risk-low-bg); border-color: var(--rs-risk-low-border);
}
.st-RUNNING, .st-PENDING, .st-WAIVED {
  color: var(--rs-risk-medium-text); background: var(--rs-risk-medium-bg); border-color: var(--rs-risk-medium-border);
}
.st-FAILED, .st-DEAD, .st-ERROR, .st-CHANGES_REQUESTED {
  color: var(--rs-risk-high-text); background: var(--rs-risk-high-bg); border-color: var(--rs-risk-high-border);
}
.st-CANCELED, .st-CLOSED {
  color: var(--rs-risk-none-text); background: var(--rs-risk-none-bg); border-color: var(--rs-risk-none-border);
}

/* ---- 详情错误态(四态规约:error = el-alert) ---- */
.err-alert { margin-top: var(--sp-3); }

/* ---- 窄屏:与原版 hide-sm 语义等价(降为 类型+状态 两列) ---- */
@media (max-width: 960px) {
  .row-ailog { grid-template-columns: 1fr auto; }
  .row-ailog .hide-sm { display: none; }
}

@media (prefers-reduced-motion: reduce) {
  .log-group-head .caret, .log-task-group .list-row { transition: none; }
}
</style>
