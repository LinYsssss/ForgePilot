<template>
  <div class="view">
      <div class="panel">
        <div class="panel-head">
          <div><h2>AI 调用日志</h2><div class="sub">{{ aiLogScope }} · 本页 {{ aiLogs.length }} 条，按日期归类</div></div>
          <div class="head-actions">
            <template v-if="aiLogTotalPages > 1">
              <button class="sm secondary" :disabled="aiLogPage === 0" @click="run(prevAiLogPage)">← 上一页</button>
              <span class="muted mono">{{ aiLogPage + 1 }}/{{ aiLogTotalPages }}</span>
              <button class="sm secondary" :disabled="aiLogPage + 1 >= aiLogTotalPages" @click="run(nextAiLogPage)">下一页 →</button>
            </template>
            <button class="sm secondary" @click="run(openProjectAiLogs)">刷新项目日志</button>
          </div>
        </div>
        <div v-if="!aiLogs.length" class="empty"><div class="ico" aria-hidden="true">◷</div><p>暂无调用日志</p><p>执行一次审查或检索后会生成。</p></div>
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
      <div class="panel" v-if="selectedAiLog">
        <div class="panel-head"><div><h2>调用详情 #{{ selectedAiLog.id }}</h2><div class="sub">{{ fmtTime(selectedAiLog.createdAt) }}</div></div><button class="sm secondary" @click="selectedAiLog = null">收起</button></div>
        <div class="grid two">
          <div class="kv"><b>类型</b><span>{{ selectedAiLog.requestType }}</span></div>
          <div class="kv"><b>Provider</b><span>{{ selectedAiLog.provider }}</span></div>
          <div class="kv"><b>模型</b><span>{{ selectedAiLog.model }}</span></div>
          <div class="kv"><b>耗时</b><span>{{ selectedAiLog.latencyMs }} ms</span></div>
          <div class="kv"><b>输入</b><span>{{ selectedAiLog.promptChars }} 字符</span></div>
          <div class="kv"><b>输出</b><span>{{ selectedAiLog.responseChars }} 字符</span></div>
          <div class="kv"><b>Token</b><span>{{ selectedAiLog.promptTokens }} 入 / {{ selectedAiLog.completionTokens }} 出 / {{ selectedAiLog.totalTokens }} 总</span></div>
        </div>
        <p v-if="selectedAiLog.errorMessage" class="badge plain risk-HIGH" style="display:block;padding:10px;margin-top:10px">{{ selectedAiLog.errorMessage }}</p>
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
