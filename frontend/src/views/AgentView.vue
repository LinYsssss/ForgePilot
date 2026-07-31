<template>
    <div class="panel">
      <div class="panel-head"><div><h2>Agent 审查与 Patch 审批</h2><div class="sub">查看 Timeline、Finding 证据、验证日志与候选 Patch</div></div></div>
      <div v-if="agentRunDetail" class="agent-live-status">
        <span class="status-pill" :class="'st-' + agentRunDetail.status" :title="agentRunDetail.status">{{ statusLabel(agentRunDetail.status) }}</span>
        <span>{{ agentPolling ? '正在自动刷新持久化状态' : (agentRunDetail.terminal ? '运行已结束' : '自动刷新已暂停') }}</span>
        <button v-if="!agentRunDetail.terminal" class="sm danger" :disabled="busy.agentControl" @click="askCancelAgentRun">取消运行</button>
        <button v-if="['FAILED','TIMED_OUT'].includes(agentRunDetail.status)" class="sm secondary" :disabled="busy.agentControl" @click="askRetryAgentRun">重试失败步骤</button>
      </div>
      <div class="grid three">
        <label class="field">Run 状态筛选
          <select v-model="agentRunFilter">
            <option value="ALL">全部（{{ agentRuns.length }}）</option><option value="ACTIVE">运行中（{{ agentRunCounts.active }}）</option><option value="WAITING">等待审批（{{ agentRunCounts.waiting }}）</option><option value="FAILED">失败（{{ agentRunCounts.failed }}）</option><option value="DONE">已完成（{{ agentRunCounts.done }}）</option>
          </select>
        </label>
        <label class="field">最近 Agent Run
          <select v-model.number="agentRunId" @change="selectAgentRun">
            <option :value="null">请选择</option>
            <option v-for="runItem in filteredAgentRuns" :key="runItem.id" :value="runItem.id">#{{ runItem.id }} · {{ statusLabel(runItem.status) }} · {{ shortCommit(runItem.headSha) }}</option>
          </select>
          <small v-if="!filteredAgentRuns.length" class="field-hint">当前筛选没有匹配项，<button type="button" class="inline-link" @click="agentRunFilter = 'ALL'">显示全部</button></small>
        </label>
        <label class="field">当前 Head SHA<input v-model="agentHeadSha" /></label>
        <div class="actions"><button :disabled="busy.agentRuns" class="secondary" @click="run(loadAgentRuns)">刷新列表</button><button :disabled="!agentRunId || busy.agent" @click="run(loadAgentWorkspace)">加载</button></div>
      </div>
    </div>
    <AgentReviewWorkspace v-if="agentRunId && agentPatch" :project-id="activeProject.projectId"
      :agent-run-id="agentRunId" :current-head-sha="agentHeadSha" :timeline="agentTimeline"
      :findings="agentFindings" :patch="agentPatch" @decided="onPatchDecided" @error="onPatchError" />
    <div v-else class="empty"><p>输入 Agent Run ID 与当前 Head SHA 后加载候选 Patch。</p></div>
</template>

<script setup>
import AgentReviewWorkspace from '../components/agent/AgentReviewWorkspace.vue'
import { shortCommit } from '../utils/format'
import { statusLabel } from '../utils/labels'
import { useBusy } from '../composables/useBusy'
import { useSession } from '../composables/useSession'
import { useAgentWorkspace } from '../composables/useAgentWorkspace'

const { busy, run } = useBusy()
const { activeProject } = useSession()
const { agentRuns, agentRunId, agentRunFilter, agentHeadSha, agentTimeline, agentFindings, agentPatch, agentRunDetail, agentPolling, filteredAgentRuns, agentRunCounts, loadAgentWorkspace, loadAgentRuns, selectAgentRun, onPatchDecided, onPatchError, askCancelAgentRun, askRetryAgentRun } = useAgentWorkspace()
</script>
