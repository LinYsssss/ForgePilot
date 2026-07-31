<template>
    <div class="panel">
      <div class="panel-head">
        <div><h2>发起代码审查</h2><div class="sub">留空 Commit 则默认审查最新提交</div></div>
        <span v-if="pollingActive" class="status-pill st-RUNNING">审查进行中…</span>
      </div>
      <div class="grid three">
        <label class="field">Commit ID<input v-model="reviewForm.commitId" :placeholder="selectedCommit ? shortCommit(selectedCommit.commitId) : '留空=最新'" /></label>
        <label class="field">Base Commit<input v-model="reviewForm.baseCommitId" placeholder="可选，留空=父提交" /></label>
        <label class="field">分支<input v-model="reviewForm.branch" :placeholder="activeProject ? activeProject.defaultBranch : 'main'" /></label>
      </div>

      <KnowledgeDocPicker v-model="reviewDocs" :documents="documents" show-meta show-count
        title="参与审查的知识库"
        empty-hint="该项目暂无知识库文档；不选则审查仅基于代码 diff。可在“知识库”页上传。" />

      <div class="actions">
        <button @click="run(createReview)" :disabled="busy.review">
          <span v-if="busy.review" class="spinner"></span>触发审查
        </button>
        <button class="secondary" @click="run(loadReviews)" :disabled="busy.reviews">刷新列表</button>
      </div>
    </div>

    <div class="split">
      <div class="panel">
        <div class="panel-head"><div><h2>审查任务</h2><div class="sub">{{ tasks.length }} 条</div></div></div>
        <div v-if="!tasks.length" class="empty"><div class="ico" aria-hidden="true">✓</div><p>暂无任务</p></div>
        <div v-else class="list">
          <div v-for="t in tasks" :key="t.taskId" class="list-row row-tasks" :class="{ selected: activeTask && activeTask.taskId === t.taskId }" @click="selectTask(t)">
            <span class="mono">#{{ t.taskId }}</span>
            <span class="grow mono">{{ shortCommit(t.commitId) }}</span>
            <span class="status-pill" :class="'st-' + t.status">{{ statusLabel(t.status) }}</span>
            <span class="row-actions">
              <button v-if="t.status === 'PENDING' || t.status === 'RUNNING'" class="warn sm" title="停止任务" @click.stop="run(() => cancelTask(t))">停止</button>
              <button class="danger sm" title="删除任务" @click.stop="askDeleteTask(t)">删除</button>
            </span>
          </div>
        </div>
      </div>
      <div class="panel">
        <div class="panel-head"><div><h2>审查报告</h2><div class="sub">{{ reports.length }} 条</div></div></div>
        <div v-if="!reports.length" class="empty"><div class="ico" aria-hidden="true">▦</div><p>暂无报告</p></div>
        <div v-else class="list">
          <div v-for="r in reports" :key="r.reportId" class="list-row row-reports" :class="{ selected: reportDetail && reportDetail.reportId === r.reportId }" @click="openReport(r.reportId)">
            <span class="mono">#{{ r.reportId }}</span>
            <span class="grow">{{ r.issueCount }} 个问题</span>
            <span class="badge" :class="'risk-' + r.overallRisk">{{ r.overallRisk }}</span>
            <span class="row-actions">
              <button class="danger sm" title="删除报告" @click.stop="askDeleteReport(r)">删除</button>
            </span>
          </div>
        </div>
      </div>
    </div>

    <div class="panel" v-if="activeTask">
      <div class="panel-head">
        <div><h2>任务 #{{ activeTask.taskId }} 详情</h2><div class="sub">{{ statusLabel(activeTask.status) }} · <span class="mono">{{ shortCommit(activeTask.commitId) }}</span></div></div>
        <div class="head-actions">
          <button v-if="activeTask.status === 'PENDING' || activeTask.status === 'RUNNING'" class="sm warn" @click="run(() => cancelTask(activeTask))">停止任务</button>
          <button class="sm secondary" @click="run(() => loadMqLogs(activeTask.taskId))">MQ 日志</button>
          <button class="sm secondary" @click="run(() => openTaskAiLogs(activeTask.taskId))">AI 日志</button>
        </div>
      </div>
      <p v-if="activeTask.errorMessage" class="badge plain risk-HIGH" style="display:block;padding:10px">错误：{{ activeTask.errorMessage }}</p>
      <div v-if="mqLogs.length" class="list">
        <div v-for="(l, i) in mqLogs" :key="i" class="list-row" style="grid-template-columns: 110px 1fr auto; cursor:default">
          <span class="status-pill" :class="'st-' + mqStatusClass(l.status)">{{ l.status }}</span>
          <span class="grow mono">{{ l.routingKey }}</span>
          <span class="mono">重试 {{ l.retryCount }}</span>
        </div>
      </div>
    </div>

    <div class="panel" v-if="reportDetail">
      <div class="panel-head">
        <div><h2>审查报告 #{{ reportDetail.reportId }}</h2><div class="sub"><span class="mono">{{ shortCommit(reportDetail.commitId) }}</span> · {{ fmtTime(reportDetail.createdAt) }}</div></div>
        <div class="head-actions">
          <button class="sm secondary" :disabled="busy.export" @click="run(() => exportReport('markdown'), 'export')">导出 Markdown</button>
          <button class="sm secondary" :disabled="busy.export" @click="run(() => exportReport('sarif'), 'export')">导出 SARIF</button>
          <button class="sm danger" @click="askDeleteReport(reportDetail)">删除报告</button>
        </div>
      </div>

      <ReportSummary :report="reportDetail" />

      <div v-if="!reportDetail.issues.length" class="empty"><div class="ico" aria-hidden="true">✓</div><p>未发现明显风险</p></div>
      <div v-for="issue in sortedIssues" :key="issue.issueId" class="issue" :class="'sevbar-' + issue.severity">
        <div class="issue-head">
          <span class="badge" :class="'sev-' + issue.severity">{{ issue.severity }}</span>
          <span class="badge plain">{{ issue.category }}</span>
          <h4>{{ issue.title }}</h4>
          <span v-if="issue.filePath" class="file">{{ issue.filePath }}<template v-if="issue.lineStart">:{{ issue.lineStart }}</template></span>
        </div>
        <p>{{ issue.description }}</p>
        <div class="kv" v-if="issue.impact"><b>影响</b><span>{{ issue.impact }}</span></div>
        <div class="callout co-evidence" v-if="issue.evidence"><span class="co-tag">证据</span><span class="co-body">{{ issue.evidence }}</span></div>
        <div class="callout co-fix" v-if="issue.suggestion"><span class="co-tag">建议</span><span class="co-body">{{ issue.suggestion }}</span></div>
        <div class="conf" v-if="issue.confidence != null" :class="confClass(issue.confidence)">
          <span class="conf-label">置信度</span>
          <span class="conf-bar"><span :style="{ width: Math.round(issue.confidence*100) + '%' }"></span></span>
          <span class="conf-pct">{{ Math.round(issue.confidence*100) }}%</span>
          <span class="conf-tag">{{ confText(issue.confidence) }}</span>
        </div>

        <div class="issue-foot">
          <button class="vote sm" :class="voteClass(issue.issueId, 'TRUE_POSITIVE')" @click="run(() => vote(issue.issueId, 'TRUE_POSITIVE'))">👍 真实问题</button>
          <button class="vote sm" :class="voteClass(issue.issueId, 'FALSE_POSITIVE')" @click="run(() => vote(issue.issueId, 'FALSE_POSITIVE'))">🚫 误报</button>
          <button class="vote sm" :class="voteClass(issue.issueId, 'NEED_DISCUSSION')" @click="run(() => vote(issue.issueId, 'NEED_DISCUSSION'))">💬 需讨论</button>
          <button class="sm secondary" @click="toggleFeedback(issue.issueId)">{{ openFeedback[issue.issueId] ? '收起反馈' : `查看反馈${feedbackCount(issue.issueId)}` }}</button>
          <button v-if="myVote(issue.issueId)" class="sm danger" @click="run(() => removeMyFeedback(issue.issueId))">撤回我的反馈</button>
        </div>

        <div v-if="openFeedback[issue.issueId]">
          <div class="fb-row">
            <input :value="ensureDraft(issue.issueId).comment" @input="e => ensureDraft(issue.issueId).comment = e.target.value" placeholder="给当前投票补充说明（可选）" @keyup.enter="run(() => submitFeedbackForm(issue.issueId))" />
            <button class="sm" @click="run(() => submitFeedbackForm(issue.issueId))" :disabled="!myVote(issue.issueId)">保存说明</button>
          </div>
          <p v-if="!myVote(issue.issueId)" class="fb-empty">先在上方选择一个投票（真实问题 / 误报 / 需讨论），再补充说明。</p>
          <div class="fb-list" v-if="(feedbackMap[issue.issueId] || []).length">
            <div v-for="fb in feedbackMap[issue.issueId]" :key="fb.feedbackId" class="fb-item" :class="{ mine: fb.mine }">
              <span class="badge plain" :class="fbBadge(fb.feedbackType)">{{ fbLabel(fb.feedbackType) }}</span>
              <span class="who">{{ fb.username }}</span>
              <span v-if="fb.mine" class="you-tag">你</span>
              <span class="grow">{{ fb.comment || '—' }}</span>
              <span class="when">{{ fmtTime(fb.updatedAt || fb.createdAt) }}</span>
            </div>
          </div>
          <p v-else class="fb-empty">还没有反馈记录。</p>
        </div>
      </div>
    </div>
</template>

<script setup>
import KnowledgeDocPicker from '../components/KnowledgeDocPicker.vue'
import ReportSummary from '../components/ReportSummary.vue'
import { fmtTime, shortCommit } from '../utils/format.js'
import { statusLabel, mqStatusClass, fbLabel, fbBadge, confClass, confText } from '../utils/labels.js'
import { useBusy } from '../composables/useBusy.js'
import { useSession } from '../composables/useSession.js'
import { useRepository } from '../composables/useRepository.js'
import { useKnowledge } from '../composables/useKnowledge.js'
import { useReviews } from '../composables/useReviews.js'
import { useFeedback } from '../composables/useFeedback.js'
import { useWorkspace } from '../composables/useWorkspace.js'

const { busy, run } = useBusy()
const { activeProject } = useSession()
const { selectedCommit } = useRepository()
const { documents, reviewDocs } = useKnowledge()
const { reviewForm, tasks, reports, activeTask, reportDetail, mqLogs, pollingActive, sortedIssues, createReview, loadReviews, selectTask, loadMqLogs, cancelTask, askDeleteTask, exportReport, askDeleteReport } = useReviews()
const { openFeedback, feedbackMap, ensureDraft, myVote, voteClass, feedbackCount, toggleFeedback, vote, submitFeedbackForm, removeMyFeedback } = useFeedback()
const { openReport, openTaskAiLogs } = useWorkspace()
</script>
