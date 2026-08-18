<template>
  <div class="view">
      <el-card shadow="never">
        <template #header>
          <div class="card-head">
            <div><h2>发起代码审查</h2><div class="card-sub">留空 Commit 则默认审查最新提交</div></div>
            <span v-if="pollingActive" class="status-pill st-RUNNING">审查进行中…</span>
          </div>
        </template>
        <el-form label-position="top" @submit.prevent>
          <el-row :gutter="16">
            <el-col :xs="24" :sm="8">
              <el-form-item label="Commit ID">
                <el-input v-model="reviewForm.commitId" :placeholder="selectedCommit ? shortCommit(selectedCommit.commitId) : '留空=最新'" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="8">
              <el-form-item label="Base Commit">
                <el-input v-model="reviewForm.baseCommitId" placeholder="可选，留空=父提交" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="8">
              <el-form-item label="分支">
                <el-input v-model="reviewForm.branch" :placeholder="activeProject ? activeProject.defaultBranch : 'main'" />
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>

        <KnowledgeDocPicker v-model="reviewDocs" :documents="documents" show-meta show-count
          title="参与审查的知识库"
          empty-hint="该项目暂无知识库文档；不选则审查仅基于代码 diff。可在“知识库”页上传。" />

        <el-space wrap :size="12" class="rs-actions">
          <el-button type="primary" :loading="busy.review" :disabled="busy.review" @click="run(createReview)">触发审查</el-button>
          <el-button type="primary" :loading="busy.compare" :disabled="busy.compare" title="同一提交自动创建两个任务：带全部知识库 vs 不带知识库" @click="run(createCompareReview)">⚖ 对比审查</el-button>
          <el-button :disabled="busy.reviews" @click="run(loadReviews)">刷新列表</el-button>
        </el-space>
      </el-card>

      <ReviewCompare />

      <div class="split">
        <el-card shadow="never">
          <template #header>
            <div class="card-head"><div><h2>审查任务</h2><div class="card-sub">{{ tasks.length }} 条</div></div></div>
          </template>
          <el-empty v-if="!tasks.length" description="暂无任务" :image-size="88" />
          <div v-else class="list">
            <div v-for="t in tasks" :key="t.taskId" class="list-row row-tasks" :class="{ selected: activeTask && activeTask.taskId === t.taskId }" @click="selectTask(t)">
              <span class="mono">#{{ t.taskId }}</span>
              <span class="grow mono">{{ shortCommit(t.commitId) }}</span>
              <span class="status-pill" :class="'st-' + t.status">{{ statusLabel(t.status) }}</span>
              <span class="row-actions">
                <el-button v-if="t.status === 'PENDING' || t.status === 'RUNNING'" size="small" type="warning" plain title="停止任务" @click.stop="run(() => cancelTask(t))">停止</el-button>
                <el-button size="small" type="danger" plain title="删除任务" @click.stop="askDeleteTask(t)">删除</el-button>
              </span>
            </div>
          </div>
        </el-card>
        <el-card shadow="never">
          <template #header>
            <div class="card-head"><div><h2>审查报告</h2><div class="card-sub">{{ reports.length }} 条</div></div></div>
          </template>
          <el-empty v-if="!reports.length" description="暂无报告" :image-size="88" />
          <div v-else class="list">
            <div v-for="r in reports" :key="r.reportId" class="list-row row-reports" :class="{ selected: reportDetail && reportDetail.reportId === r.reportId }" @click="openReport(r.reportId)">
              <span class="mono">#{{ r.reportId }}</span>
              <span class="grow">{{ r.issueCount }} 个问题</span>
              <span class="badge" :class="'risk-' + r.overallRisk">{{ r.overallRisk }}</span>
              <span class="row-actions">
                <el-button size="small" type="danger" plain title="删除报告" @click.stop="askDeleteReport(r)">删除</el-button>
              </span>
            </div>
          </div>
        </el-card>
      </div>

      <el-card shadow="never" v-if="activeTask">
        <template #header>
          <div class="card-head">
            <div><h2>任务 #{{ activeTask.taskId }} 详情</h2><div class="card-sub">{{ statusLabel(activeTask.status) }} · <span class="mono">{{ shortCommit(activeTask.commitId) }}</span></div></div>
            <div class="head-actions">
              <el-button v-if="activeTask.status === 'PENDING' || activeTask.status === 'RUNNING'" size="small" type="warning" plain @click="run(() => cancelTask(activeTask))">停止任务</el-button>
              <el-button size="small" @click="run(() => loadMqLogs(activeTask.taskId))">MQ 日志</el-button>
              <el-button size="small" @click="run(() => openTaskAiLogs(activeTask.taskId))">AI 日志</el-button>
            </div>
          </div>
        </template>
        <el-alert v-if="activeTask.errorMessage" class="err-alert" type="error" :closable="false" :title="'错误：' + activeTask.errorMessage" />
        <div v-if="mqLogs.length" class="list">
          <div v-for="(l, i) in mqLogs" :key="i" class="list-row" style="grid-template-columns: 110px 1fr auto; cursor:default">
            <span class="status-pill" :class="'st-' + mqStatusClass(l.status)">{{ l.status }}</span>
            <span class="grow mono">{{ l.routingKey }}</span>
            <span class="mono">重试 {{ l.retryCount }}</span>
          </div>
        </div>
      </el-card>

      <el-card shadow="never" v-if="reportDetail">
        <template #header>
          <div class="card-head">
            <div><h2>审查报告 #{{ reportDetail.reportId }}</h2><div class="card-sub"><span class="mono">{{ shortCommit(reportDetail.commitId) }}</span> · {{ fmtTime(reportDetail.createdAt) }}</div></div>
            <div class="head-actions">
              <el-button size="small" :disabled="busy.export" @click="run(() => exportReport('markdown'), 'export')">导出 Markdown</el-button>
              <el-button size="small" :disabled="busy.export" @click="run(() => exportReport('sarif'), 'export')">导出 SARIF</el-button>
              <el-button size="small" type="danger" plain @click="askDeleteReport(reportDetail)">删除报告</el-button>
            </div>
          </div>
        </template>

        <ReportSummary :report="reportDetail" />

        <!-- P4a:AC 覆盖三态结论区块(有关联需求的任务才有;历史报告无此字段) -->
        <div v-if="reportDetail.coverage && reportDetail.coverage.coverage && reportDetail.coverage.coverage.length" class="coverage-block">
          <h3 class="coverage-title">
            需求一致性:{{ reportDetail.coverage.requirementCode }} {{ reportDetail.coverage.requirementTitle }}
          </h3>
          <div v-for="ac in reportDetail.coverage.coverage" :key="ac.acId" class="coverage-row" :class="'cov-' + ac.verdict">
            <div class="coverage-head">
              <span class="badge" :class="covBadge(ac.verdict)">{{ covLabel(ac.verdict) }}</span>
              <b>{{ ac.acId }}</b>
              <span class="coverage-ac">{{ ac.acText }}</span>
            </div>
            <p v-if="ac.rationale" class="coverage-rationale">{{ ac.rationale }}</p>
            <div v-for="(ev, i) in ac.evidence" :key="i" class="callout co-evidence">
              <span class="co-tag">证据</span>
              <span class="co-body mono">{{ ev.filePath }}<template v-if="ev.lineStart">:{{ ev.lineStart }}<template v-if="ev.lineEnd && ev.lineEnd !== ev.lineStart">-{{ ev.lineEnd }}</template></template></span>
              <span v-if="ev.note" class="co-body">{{ ev.note }}</span>
            </div>
          </div>
        </div>

        <el-empty v-if="!reportDetail.issues.length" description="未发现明显风险" :image-size="88" />
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
            <el-button size="small" class="vote" :class="voteClass(issue.issueId, 'TRUE_POSITIVE')" @click="run(() => vote(issue.issueId, 'TRUE_POSITIVE'))">👍 真实问题</el-button>
            <el-button size="small" class="vote" :class="voteClass(issue.issueId, 'FALSE_POSITIVE')" @click="run(() => vote(issue.issueId, 'FALSE_POSITIVE'))">🚫 误报</el-button>
            <el-button size="small" class="vote" :class="voteClass(issue.issueId, 'NEED_DISCUSSION')" @click="run(() => vote(issue.issueId, 'NEED_DISCUSSION'))">💬 需讨论</el-button>
            <el-button size="small" @click="toggleFeedback(issue.issueId)">{{ openFeedback[issue.issueId] ? '收起反馈' : `查看反馈${feedbackCount(issue.issueId)}` }}</el-button>
            <el-button v-if="myVote(issue.issueId)" size="small" type="danger" plain @click="run(() => removeMyFeedback(issue.issueId))">撤回我的反馈</el-button>
          </div>

          <div v-if="openFeedback[issue.issueId]">
            <div class="fb-row">
              <el-input class="fb-input" :model-value="ensureDraft(issue.issueId).comment" placeholder="给当前投票补充说明（可选）"
                @update:model-value="v => (ensureDraft(issue.issueId).comment = v)" @keyup.enter="run(() => submitFeedbackForm(issue.issueId))" />
              <el-button size="small" :disabled="!myVote(issue.issueId)" @click="run(() => submitFeedbackForm(issue.issueId))">保存说明</el-button>
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
      </el-card>
  </div>
</template>

<script setup>
import KnowledgeDocPicker from '../../components/KnowledgeDocPicker.vue'
import ReviewCompare from '../../components/ReviewCompare.vue'
import ReportSummary from '../../components/ReportSummary.vue'
import { fmtTime, shortCommit } from '../../utils/format.js'
import { statusLabel, mqStatusClass, fbLabel, fbBadge, confClass, confText } from '../../utils/labels.js'
import { useBusy } from '../../composables/useBusy.js'
import { useSession } from '../../composables/useSession.js'
import { useRepository } from '../../composables/useRepository.js'
import { useKnowledge } from '../../composables/useKnowledge.js'
import { useReviews } from '../../composables/useReviews.js'
import { useFeedback } from '../../composables/useFeedback.js'
import { useWorkspace } from '../../composables/useWorkspace.js'

const { busy, run } = useBusy()
const { activeProject } = useSession()
const { selectedCommit } = useRepository()
const { documents, reviewDocs } = useKnowledge()
const { reviewForm, tasks, reports, activeTask, reportDetail, mqLogs, pollingActive, sortedIssues, createReview, createCompareReview, loadReviews, selectTask, loadMqLogs, cancelTask, askDeleteTask, exportReport, askDeleteReport } = useReviews()
const { openFeedback, feedbackMap, ensureDraft, myVote, voteClass, feedbackCount, toggleFeedback, vote, submitFeedbackForm, removeMyFeedback } = useFeedback()
const { openReport, openTaskAiLogs } = useWorkspace()

// P4a coverage 三态标签与徽章(沿用 .badge 的 sev-* 配色语义:风险=红、通过=既有 plain)。
function covLabel(verdict) {
  return { COVERED: '已覆盖', NOT_FOUND: '未发现', AT_RISK: '存在风险' }[verdict] || verdict
}
function covBadge(verdict) {
  return { COVERED: 'cov-ok', NOT_FOUND: 'sev-HIGH', AT_RISK: 'sev-MEDIUM' }[verdict] || 'plain'
}
</script>

<style scoped>
/* Precision Workbench · 审查工作台。Element 结构 + tokens 直供。
   证据链路保真:issue 卡的 file:line 锚点、证据/建议双 callout 原本即为
   内嵌块,保持内嵌不改抽屉(保真 > 组件化);composables/utils 调用零改动。
   scoped 同名重定义清单(styles.css 冻结期间由本段供视觉,styles.css
   删除后本页不受影响):.split、.list/.list-row(.selected/.grow/.mono)/
   .row-tasks/.row-reports/.row-actions、.status-pill(.st-* 全族;RUNNING/
   PENDING 脉冲由全局 keyframes 现供、tokens.css @layer 接管,与 PR 页同法)、
   .badge(.plain、.risk-*、.sev-* 含 CRITICAL/INFO 补齐)、.issue(.sevbar-* 五级)/
   .issue-head(h4/.file)/.kv/.issue-foot、.callout(.co-tag/.co-body/
   .co-evidence/.co-fix)、.conf(.conf-label/.conf-bar/.conf-pct/.conf-tag/
   .c-high/.c-mid/.c-low 三档)、投票三态 .vote.on-*(el-button CSS 变量接口)、
   .fb-row/.fb-list/.fb-item(.mine/.who/.you-tag/.when)/.fb-empty、.mono */
.view {
  display: grid;
  gap: var(--sp-5);
  align-content: start;
  font-family: var(--rs-font-body);
  color: var(--rs-text);
}

/* ---- 卡头(h2 显式定字族,躲开 styles.css 元素级 --font-display 泄漏) ---- */
.card-head { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); flex-wrap: wrap; }
.card-head h2 { margin: 0; font-family: var(--rs-font-body); font-size: var(--rs-fs-lg); font-weight: 700; color: var(--rs-text); }
.card-sub { margin-top: 3px; color: var(--rs-text-dim); font-size: var(--rs-fs-sm); font-weight: 400; }
.head-actions { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.head-actions .el-button + .el-button { margin-left: 0; }

/* ---- P4a:需求一致性 coverage 区块 ---- */
.coverage-block { margin: var(--sp-4) 0; padding: var(--sp-3) var(--sp-4); border: 1px solid var(--rs-line, #e0e0e0); border-radius: 8px; }
.coverage-title { margin: 0 0 var(--sp-3); font-size: var(--rs-fs-md); color: var(--rs-text); }
.coverage-row { padding: var(--sp-2) 0; border-top: 1px dashed var(--rs-line, #e0e0e0); }
.coverage-row:first-of-type { border-top: 0; }
.coverage-head { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.coverage-ac { color: var(--rs-text); }
.coverage-rationale { margin: var(--sp-1) 0 0; color: var(--rs-text-dim); font-size: var(--rs-fs-sm); }
.badge.cov-ok { background: rgba(64, 160, 112, 0.14); color: #2f7d54; }

.mono { font-family: var(--rs-font-mono); font-feature-settings: "liga" 0; }

.rs-actions { margin-top: var(--sp-5); }

/* ---- 任务/报告双栏(同名重定义;960 以下折单栏,与原全局断点一致) ---- */
.split { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-4); }

/* ---- 小列表(保真行结构,与 PR 页报告列表同构;点选/选中语义不变) ---- */
.list { display: grid; gap: var(--sp-2); }
.list-row {
  display: grid; align-items: center; gap: var(--sp-3);
  padding: 11px 14px;
  background: var(--rs-surface);
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-sm);
  color: var(--rs-text);
  font-size: var(--rs-fs-base); font-weight: 500;
  text-align: left; cursor: pointer; min-height: 0;
  transition: border-color var(--rs-t-fast) var(--rs-ease-out), background var(--rs-t-fast), box-shadow var(--rs-t-fast);
}
/* 选择器保持旧全局 .list-row:hover:not(:disabled) 同形(styles.css 冻结期
   借此压过它,该全局现已退役),顺带清掉 Observatory 的位移/阴影动效 */
.list-row:hover:not(:disabled) {
  border-color: var(--el-color-primary-light-5);
  background: var(--rs-fill-lighter);
  transform: none; box-shadow: none;
}
.list-row.selected {
  border-color: var(--rs-primary);
  background: var(--el-color-primary-light-9);
  box-shadow: inset 3px 0 0 var(--rs-primary);
}
.list-row .mono { font-size: var(--rs-fs-sm); color: var(--rs-text-soft); }
.list-row .grow { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.row-tasks { grid-template-columns: 52px 1fr auto auto; }
.row-reports { grid-template-columns: 58px 1fr auto auto; }
.row-actions { display: flex; gap: var(--sp-1); align-items: center; }
.row-actions .el-button + .el-button { margin-left: 0; }

/* ---- 任务错误信息(四态规约:error = el-alert) ---- */
.err-alert { margin-bottom: var(--sp-3); }

/* ---- 徽标(badge 同名重定义:去 Observatory 辉光,补 1px 语义描边;
   risk-* 走报告四级、sev-* 走 finding 五级,色板均出自 tokens 唯一映射) ---- */
.badge {
  display: inline-flex; align-items: center; gap: var(--sp-1);
  padding: 2px 9px;
  border: 1px solid transparent;
  border-radius: var(--rs-radius-round);
  font-size: var(--rs-fs-xs); font-weight: 700; letter-spacing: 0.02em;
  line-height: 1.5; white-space: nowrap;
}
.badge::before { content: ""; width: 6px; height: 6px; border-radius: 50%; background: currentColor; box-shadow: none; }
.badge.plain::before { content: none; display: none; }
.badge.plain { background: var(--rs-fill); border-color: transparent; color: var(--rs-text-soft); font-family: var(--rs-font-mono); font-weight: 600; }
.risk-HIGH, .sev-HIGH { color: var(--rs-risk-high-text); background: var(--rs-risk-high-bg); border-color: var(--rs-risk-high-border); }
.risk-MEDIUM, .sev-MEDIUM { color: var(--rs-risk-medium-text); background: var(--rs-risk-medium-bg); border-color: var(--rs-risk-medium-border); }
.risk-LOW, .sev-LOW { color: var(--rs-risk-low-text); background: var(--rs-risk-low-bg); border-color: var(--rs-risk-low-border); }
.risk-NONE, .sev-NONE { color: var(--rs-risk-none-text); background: var(--rs-risk-none-bg); border-color: var(--rs-risk-none-border); }
.sev-CRITICAL { color: var(--rs-sev-critical-text); background: var(--rs-sev-critical-bg); border-color: var(--rs-sev-critical-border); }
.sev-INFO { color: var(--rs-sev-info-text); background: var(--rs-sev-info-bg); border-color: var(--rs-sev-info-border); }

/* ---- 状态 pill(st-* 同名重定义;分组即 design-tokens.md 全站唯一映射。
   本页现值:任务 PENDING/RUNNING/SUCCESS/FAILED/DEAD/CANCELED、
   MQ 映射 SUCCESS/FAILED/DEAD,整族收录保持全站一致) ---- */
.status-pill {
  display: inline-flex; align-items: center; gap: var(--sp-2);
  padding: 3px 10px;
  border: 1px solid transparent;
  border-radius: var(--rs-radius-round);
  font-size: var(--rs-fs-xs); font-weight: 700; line-height: 1.5; white-space: nowrap;
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

/* ---- issue 卡:左侧严重度色条(sevbar 五级,solid 梯度)+ 白卡描边 ---- */
.issue {
  border: 1px solid var(--rs-border);
  border-left: 3px solid var(--rs-border);
  border-radius: var(--rs-radius-sm);
  padding: var(--sp-5);
  margin-bottom: var(--sp-3);
  background: var(--rs-surface);
  transition: border-color var(--rs-t-fast), box-shadow var(--rs-t-fast);
}
.issue:hover { box-shadow: var(--rs-shadow-sm); }
.issue.sevbar-CRITICAL { border-left-color: var(--rs-sev-critical-solid); }
.issue.sevbar-HIGH { border-left-color: var(--rs-risk-high-solid); }
.issue.sevbar-MEDIUM { border-left-color: var(--rs-risk-medium-solid); }
.issue.sevbar-LOW { border-left-color: var(--rs-risk-low-solid); }
.issue.sevbar-INFO { border-left-color: var(--rs-sev-info-solid); }

.issue-head { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; margin-bottom: var(--sp-2); }
.issue-head h4 { margin: 0 0 0 2px; font-family: var(--rs-font-body); font-size: var(--rs-fs-md); font-weight: 700; color: var(--rs-text); }
.issue-head .file {
  font-family: var(--rs-font-mono); font-size: var(--rs-fs-xs); color: var(--rs-text-dim);
  margin-left: auto; padding: 3px 8px;
  background: var(--rs-fill); border: 1px solid var(--rs-border-faint); border-radius: var(--rs-radius-sm);
}
.issue p { margin: var(--sp-2) 0; color: var(--rs-text-soft); font-size: var(--rs-fs-base); line-height: 1.6; }
.issue .kv { display: grid; grid-template-columns: 68px 1fr; gap: var(--sp-3); margin: var(--sp-2) 0; align-items: start; }
.issue .kv b { color: var(--rs-text-dim); font-size: var(--rs-fs-sm); font-weight: 700; padding-top: 1px; }
.issue .kv span { color: var(--rs-text-soft); font-size: var(--rs-fs-sm); line-height: 1.6; }

/* ---- 证据/修复建议双 callout(证据=中性码面,建议=teal 强调;结构不动) ---- */
.issue .callout {
  position: relative; display: grid; grid-template-columns: auto 1fr; gap: var(--sp-3); align-items: baseline;
  margin: var(--sp-2) 0; padding: var(--sp-3) var(--sp-3) var(--sp-3) var(--sp-4);
  border-radius: var(--rs-radius-sm); border: 1px solid var(--rs-border-faint);
  transition: border-color var(--rs-t-fast) var(--rs-ease-out), background var(--rs-t-fast);
}
.issue .callout::before { content: ""; position: absolute; left: 0; top: 8px; bottom: 8px; width: 3px; border-radius: var(--rs-radius-round); }
.issue .callout .co-tag { font-size: var(--rs-fs-xs); font-weight: 800; letter-spacing: 0.04em; padding: 2px 8px; border-radius: var(--rs-radius-round); white-space: nowrap; }
.issue .callout .co-body { color: var(--rs-text-soft); font-size: var(--rs-fs-sm); line-height: 1.62; }
.co-evidence { background: var(--rs-fill-lighter); }
.co-evidence::before { background: var(--rs-text-dim); }
.co-evidence .co-tag { color: var(--rs-text-dim); background: var(--rs-fill); }
.co-evidence .co-body { font-family: var(--rs-font-mono); font-size: var(--rs-fs-xs); color: var(--rs-text-soft); word-break: break-word; }
.co-evidence:hover { border-color: var(--rs-border-strong); }
.co-fix { background: var(--el-color-primary-light-9); border-color: var(--el-color-primary-light-7); }
.co-fix::before { background: var(--rs-primary); box-shadow: none; }
.co-fix .co-tag { color: var(--el-color-white); background: var(--rs-primary); }
.co-fix:hover { border-color: var(--el-color-primary-light-3); }

/* ---- 置信度三档(c-high teal / c-mid 琥珀 / c-low 灰;纯色告别渐变) ---- */
.issue .conf { display: inline-flex; align-items: center; gap: var(--sp-2); font-size: var(--rs-fs-xs); color: var(--rs-text-dim); margin-top: var(--sp-2); }
.issue .conf .conf-label { color: var(--rs-text-dim); }
.conf-bar { width: 88px; height: 6px; border-radius: var(--rs-radius-round); background: var(--rs-border); overflow: hidden; }
.conf-bar > span {
  display: block; height: 100%;
  background: var(--rs-primary); border-radius: var(--rs-radius-round);
  transition: width var(--rs-t-slow) var(--rs-ease-out);
  animation: grow-x 0.8s var(--rs-ease-out) both;
}
/* 入场从 0 填充到内联宽度(keyframe 只声明 from,终态取内联 width) */
@keyframes grow-x { from { width: 0; } }
.issue .conf .conf-pct { color: var(--rs-text); font-weight: 700; font-family: var(--rs-font-mono); }
.issue .conf .conf-tag { font-size: var(--rs-fs-xs); font-weight: 700; padding: 1px 7px; border-radius: var(--rs-radius-round); }
.conf.c-high .conf-bar > span { background: var(--rs-primary); }
.conf.c-high .conf-tag { color: var(--rs-risk-low-text); background: var(--rs-risk-low-bg); }
.conf.c-mid .conf-bar > span { background: var(--rs-risk-medium-solid); }
.conf.c-mid .conf-tag { color: var(--rs-risk-medium-text); background: var(--rs-risk-medium-bg); }
.conf.c-low .conf-bar > span { background: var(--rs-risk-none-solid); }
.conf.c-low .conf-tag { color: var(--rs-risk-none-text); background: var(--rs-risk-none-bg); }

/* ---- issue 底部操作条 ---- */
.issue-foot {
  display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap;
  margin-top: var(--sp-4); padding-top: var(--sp-3);
  border-top: 1px dashed var(--rs-border);
}
.issue-foot .el-button + .el-button { margin-left: 0; }

/* ---- 投票三态(on-* 等价重铸:选中票面着风险语义 tint;
   走 el-button 官方 CSS 变量接口,不 :deep 硬改内部) ---- */
.el-button.vote.on-TRUE_POSITIVE {
  --el-button-bg-color: var(--rs-risk-low-bg);
  --el-button-border-color: var(--rs-risk-low-border);
  --el-button-text-color: var(--rs-risk-low-text);
  --el-button-hover-bg-color: var(--rs-risk-low-bg);
  --el-button-hover-border-color: var(--rs-risk-low-solid);
  --el-button-hover-text-color: var(--rs-risk-low-text);
  --el-button-active-bg-color: var(--rs-risk-low-bg);
  --el-button-active-border-color: var(--rs-risk-low-solid);
  --el-button-active-text-color: var(--rs-risk-low-text);
}
.el-button.vote.on-FALSE_POSITIVE {
  --el-button-bg-color: var(--rs-risk-high-bg);
  --el-button-border-color: var(--rs-risk-high-border);
  --el-button-text-color: var(--rs-risk-high-text);
  --el-button-hover-bg-color: var(--rs-risk-high-bg);
  --el-button-hover-border-color: var(--rs-risk-high-solid);
  --el-button-hover-text-color: var(--rs-risk-high-text);
  --el-button-active-bg-color: var(--rs-risk-high-bg);
  --el-button-active-border-color: var(--rs-risk-high-solid);
  --el-button-active-text-color: var(--rs-risk-high-text);
}
.el-button.vote.on-NEED_DISCUSSION {
  --el-button-bg-color: var(--rs-risk-medium-bg);
  --el-button-border-color: var(--rs-risk-medium-border);
  --el-button-text-color: var(--rs-risk-medium-text);
  --el-button-hover-bg-color: var(--rs-risk-medium-bg);
  --el-button-hover-border-color: var(--rs-risk-medium-solid);
  --el-button-hover-text-color: var(--rs-risk-medium-text);
  --el-button-active-bg-color: var(--rs-risk-medium-bg);
  --el-button-active-border-color: var(--rs-risk-medium-solid);
  --el-button-active-text-color: var(--rs-risk-medium-text);
}

/* ---- 反馈区(fb-* 家族 tokens 重铸;fb 徽标沿用 badge.plain 灰面,
   与迁移前呈现一致) ---- */
.fb-row { display: flex; gap: var(--sp-2); margin-top: var(--sp-3); align-items: flex-start; flex-wrap: wrap; }
.fb-row .fb-input { flex: 1; min-width: 180px; }
.fb-list { margin-top: var(--sp-3); display: grid; gap: var(--sp-2); }
.fb-item {
  display: flex; align-items: center; gap: var(--sp-2);
  padding: 8px 12px;
  background: var(--rs-surface);
  border: 1px solid var(--rs-border-faint);
  border-radius: var(--rs-radius-sm);
  color: var(--rs-text-soft); font-size: var(--rs-fs-sm);
}
.fb-item.mine { border-color: var(--el-color-primary-light-5); background: var(--el-color-primary-light-9); }
.fb-item .who { font-weight: 700; color: var(--rs-text); }
.fb-item .you-tag { font-size: var(--rs-fs-xs); color: var(--rs-primary); font-weight: 700; }
.fb-item .when { color: var(--rs-text-dim); font-size: var(--rs-fs-xs); font-family: var(--rs-font-mono); }
.fb-empty { color: var(--rs-text-dim); font-size: var(--rs-fs-sm); margin-top: var(--sp-3); }

/* ---- 窄屏:折单栏 + 小列表降两列(与原全局 960 断点语义一致) ---- */
@media (max-width: 960px) {
  .split { grid-template-columns: 1fr; }
  .row-tasks, .row-reports { grid-template-columns: 1fr auto; }
}

@media (prefers-reduced-motion: reduce) {
  .conf-bar > span { animation: none; }
}
</style>
