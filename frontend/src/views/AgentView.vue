<template>
  <div class="view">
    <el-card shadow="never">
      <template #header>
        <div class="rs-card-head">
          <div>
            <h2>Agent 审查与 Patch 审批</h2>
            <p class="rs-sub">查看 Timeline、Finding 证据、验证日志与候选 Patch</p>
          </div>
        </div>
      </template>
      <div v-if="agentRunDetail" class="agent-live-status">
        <span class="status-pill" :class="'st-' + agentRunDetail.status" :title="agentRunDetail.status">{{ statusLabel(agentRunDetail.status) }}</span>
        <span>{{ agentPolling ? '正在自动刷新持久化状态' : (agentRunDetail.terminal ? '运行已结束' : '自动刷新已暂停') }}</span>
        <el-button v-if="!agentRunDetail.terminal" size="small" type="danger" plain :disabled="busy.agentControl" @click="askCancelAgentRun">取消运行</el-button>
        <el-button v-if="['FAILED','TIMED_OUT'].includes(agentRunDetail.status)" size="small" :disabled="busy.agentControl" @click="askRetryAgentRun">重试失败步骤</el-button>
      </div>
      <el-form label-position="top" @submit.prevent>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="8">
            <el-form-item label="Run 状态筛选">
              <el-select v-model="agentRunFilter">
                <el-option value="ALL" :label="`全部（${agentRuns.length}）`" />
                <el-option value="ACTIVE" :label="`运行中（${agentRunCounts.active}）`" />
                <el-option value="WAITING" :label="`等待审批（${agentRunCounts.waiting}）`" />
                <el-option value="FAILED" :label="`失败（${agentRunCounts.failed}）`" />
                <el-option value="DONE" :label="`已完成（${agentRunCounts.done}）`" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="8">
            <el-form-item label="最近 Agent Run">
              <el-select v-model="agentRunId" placeholder="请选择" @change="selectAgentRun">
                <el-option :value="null" label="请选择" />
                <el-option v-for="runItem in filteredAgentRuns" :key="runItem.id" :value="runItem.id" :label="`#${runItem.id} · ${statusLabel(runItem.status)} · ${shortCommit(runItem.headSha)}`" />
              </el-select>
              <small v-if="!filteredAgentRuns.length" class="field-hint">当前筛选没有匹配项，<button type="button" class="inline-link" @click="agentRunFilter = 'ALL'">显示全部</button></small>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="8">
            <el-form-item label="当前 Head SHA">
              <el-input v-model="agentHeadSha" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <el-space wrap :size="12">
        <el-button :loading="busy.agentRuns" :disabled="busy.agentRuns" @click="run(loadAgentRuns)">刷新列表</el-button>
        <el-button type="primary" :disabled="!agentRunId || busy.agent" @click="run(loadAgentWorkspace)">加载</el-button>
      </el-space>
    </el-card>
    <AgentReviewWorkspace v-if="agentRunId && agentPatch" :project-id="activeProject.projectId"
      :agent-run-id="agentRunId" :current-head-sha="agentHeadSha" :timeline="agentTimeline"
      :findings="agentFindings" :patch="agentPatch" @decided="onPatchDecided" @error="onPatchError" />
    <el-empty v-else class="gate-empty" description="输入 Agent Run ID 与当前 Head SHA 后加载候选 Patch。" />
  </div>
</template>

<script setup>
import AgentReviewWorkspace from '../components/agent/AgentReviewWorkspace.vue'
import { shortCommit } from '../utils/format.js'
import { statusLabel } from '../utils/labels.js'
import { useBusy } from '../composables/useBusy.js'
import { useSession } from '../composables/useSession.js'
import { useAgentWorkspace } from '../composables/useAgentWorkspace.js'

const { busy, run } = useBusy()
const { activeProject } = useSession()
const { agentRuns, agentRunId, agentRunFilter, agentHeadSha, agentTimeline, agentFindings, agentPatch, agentRunDetail, agentPolling, filteredAgentRuns, agentRunCounts, loadAgentWorkspace, loadAgentRuns, selectAgentRun, onPatchDecided, onPatchError, askCancelAgentRun, askRetryAgentRun } = useAgentWorkspace()
</script>

<style scoped>
/* Precision Workbench Agent 工作台入口:Element 结构 + tokens 直供。
   scoped 同名重定义清单(脱离 styles.css 的 Observatory 视觉):
   .agent-live-status、.status-pill/.st-*(全站唯一归组,未归组的
   Agent 专属状态落 NONE 灰基线,与旧版 muted 灰等价)、
   .field-hint、.inline-link */
.view {
  display: grid;
  gap: var(--sp-5);
  align-content: start;
  font-family: var(--rs-font-body);
  color: var(--rs-text);
}

/* ---- 卡片头:标题 + 说明 ---- */
.rs-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.rs-card-head h2 {
  margin: 0;
  font-family: var(--rs-font-body);
  font-size: var(--rs-fs-lg);
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--rs-text);
}
.rs-sub {
  margin: 3px 0 0;
  font-size: var(--rs-fs-sm);
  font-weight: 400;
  color: var(--rs-text-dim);
}

/* ---- 运行状态行(轮询提示 + 取消/重试) ---- */
.agent-live-status {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: 0 0 var(--sp-4);
  color: var(--rs-text-dim);
  font-size: var(--rs-fs-sm);
}
/* 双按钮间距交给 gap;Element 相邻按钮默认 12px margin 归零 */
.agent-live-status .el-button + .el-button { margin-left: 0; }

/* ---- 状态 pill(st-* 同名重定义;归组即 design-tokens.md 全站唯一映射。
        基线给 NONE 灰:Agent Run 专属状态(COMPLETED/WAITING_APPROVAL 等)
        不在归组表内,旧版同样以中性灰呈现) ---- */
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 3px 10px;
  border: 1px solid var(--rs-risk-none-border);
  border-radius: var(--rs-radius-round);
  font-size: var(--rs-fs-xs);
  font-weight: 700;
  line-height: 1.6;
  white-space: nowrap;
  color: var(--rs-risk-none-text);
  background: var(--rs-risk-none-bg);
}
.status-pill::before { content: ""; width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.st-SUCCESS, .st-INDEXED, .st-ACTIVE, .st-CONSUMED, .st-PUBLISHED, .st-PASSED, .st-OPEN, .st-MERGED {
  color: var(--rs-risk-low-text);
  background: var(--rs-risk-low-bg);
  border-color: var(--rs-risk-low-border);
}
.st-RUNNING, .st-PENDING, .st-WAIVED {
  color: var(--rs-risk-medium-text);
  background: var(--rs-risk-medium-bg);
  border-color: var(--rs-risk-medium-border);
}
.st-FAILED, .st-DEAD, .st-ERROR, .st-CHANGES_REQUESTED {
  color: var(--rs-risk-high-text);
  background: var(--rs-risk-high-bg);
  border-color: var(--rs-risk-high-border);
}
.st-CANCELED, .st-CLOSED {
  color: var(--rs-risk-none-text);
  background: var(--rs-risk-none-bg);
  border-color: var(--rs-risk-none-border);
}

/* ---- 筛选无匹配提示(同名重定义;按钮元素级 Observatory 皮肤泄漏逐项归零) ---- */
.field-hint {
  display: block;
  width: 100%;
  margin-top: 5px;
  color: var(--rs-text-dim);
  font-size: var(--rs-fs-xs);
  line-height: 1.6;
}
.inline-link {
  display: inline;
  min-height: 0;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: none;
  box-shadow: none;
  color: var(--rs-primary);
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}
.inline-link:hover:not(:disabled) { background: none; box-shadow: none; color: var(--rs-primary-dark); text-decoration: underline; }
.inline-link:active:not(:disabled) { transform: none; }
.inline-link:focus-visible { outline: 2px solid var(--rs-primary); outline-offset: 2px; box-shadow: none; }

/* ---- 门控空态(输入 run + head sha 才展开工作台) ---- */
.gate-empty { padding: var(--sp-10) var(--sp-5); }
</style>
