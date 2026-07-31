<template>
    <div class="panel">
      <div class="panel-head">
        <div><h2>{{ pullRequestForm.pullRequestId ? '更新 PR' : '登记 PR' }}</h2><div class="sub">把真实团队里的 Pull Request 纳入审查闭环</div></div>
        <div class="head-actions">
          <button v-if="pullRequestForm.pullRequestId" class="sm secondary" @click="resetPullRequestForm">取消编辑</button>
          <button class="sm secondary" @click="fillPrFromSelectedCommit" :disabled="!selectedCommit">使用已选 Commit</button>
        </div>
      </div>
      <div class="grid four">
        <label class="field">PR 编号<input v-model.number="pullRequestForm.prNumber" type="number" min="1" placeholder="123" /></label>
        <label class="field">Provider
          <select v-model="pullRequestForm.provider">
            <option value="GITHUB">GitHub</option>
            <option value="GITLAB">GitLab</option>
            <option value="GITEE">Gitee</option>
            <option value="OTHER">其他</option>
          </select>
        </label>
        <label class="field" style="grid-column: span 2">标题<input v-model="pullRequestForm.title" placeholder="feat: add order review gate" /></label>
      </div>
      <div class="grid four" style="margin-top:16px">
        <label class="field">作者<input v-model="pullRequestForm.authorName" placeholder="developer" /></label>
        <label class="field">源分支<input v-model="pullRequestForm.sourceBranch" placeholder="feature/order-gate" /></label>
        <label class="field">目标分支<input v-model="pullRequestForm.targetBranch" :placeholder="activeProject ? activeProject.defaultBranch : 'main'" /></label>
        <label class="field">外部 PR ID<input v-model="pullRequestForm.externalPrId" placeholder="可选" /></label>
      </div>
      <div class="grid two" style="margin-top:16px">
        <label class="field">Base SHA / Ref<input v-model="pullRequestForm.baseSha" placeholder="main 或 base commit" /></label>
        <label class="field">Head SHA / Ref<input v-model="pullRequestForm.headSha" placeholder="feature 分支或 head commit" /></label>
      </div>
      <div class="actions">
        <button @click="run(savePullRequest)" :disabled="busy.pullRequest">
          <span v-if="busy.pullRequest" class="spinner"></span>{{ pullRequestForm.pullRequestId ? '保存 PR 更新' : '登记 PR' }}
        </button>
        <button class="secondary" @click="run(loadPullRequests)" :disabled="busy.pullRequests">刷新 PR</button>
      </div>
    </div>

    <div class="split">
      <div class="panel">
        <div class="panel-head"><div><h2>PR 列表</h2><div class="sub">{{ pullRequests.length }} 条</div></div></div>
        <div v-if="!pullRequests.length" class="empty"><div class="ico" aria-hidden="true">⑂</div><p>暂无 PR</p><p>先登记一个 PR，再触发审查。</p></div>
        <div v-else class="list">
          <div v-for="pr in pullRequests" :key="pr.pullRequestId" class="list-row row-prs" :class="{ selected: activePullRequest && activePullRequest.pullRequestId === pr.pullRequestId }" @click="selectPullRequest(pr)">
            <span class="mono">#{{ pr.prNumber || pr.pullRequestId }}</span>
            <span class="grow">{{ pr.title }}</span>
            <span class="status-pill" :class="'st-' + pr.reviewState">{{ prStateLabel(pr.reviewState) }}</span>
            <span class="mono">{{ shortCommit(pr.headSha) }}</span>
          </div>
        </div>
      </div>

      <div class="panel">
        <div class="panel-head">
          <div><h2>PR 审查闭环</h2><div class="sub">{{ activePullRequest ? activePullRequest.title : '请选择 PR' }}</div></div>
          <button v-if="activePullRequest" class="sm secondary" @click="editPullRequest(activePullRequest)">编辑 PR</button>
        </div>
        <div v-if="!activePullRequest" class="empty"><div class="ico" aria-hidden="true">⑂</div><p>选择一个 PR</p></div>
        <template v-else>
          <div class="pr-meta">
            <span class="badge plain">{{ activePullRequest.provider }}</span>
            <span class="status-pill" :class="'st-' + activePullRequest.status">{{ activePullRequest.status }}</span>
            <span class="mono">{{ activePullRequest.sourceBranch }} → {{ activePullRequest.targetBranch }}</span>
          </div>

          <KnowledgeDocPicker v-model="prDocs" :documents="documents" compact
            title="参与 PR 审查的知识库"
            empty-hint="该项目暂无知识库文档；不选则审查仅基于 PR diff。" />

          <div class="actions">
            <button @click="run(createPrReview)" :disabled="busy.prReview">
              <span v-if="busy.prReview" class="spinner"></span>触发 PR 审查
            </button>
            <button class="secondary" @click="run(loadReviews)" :disabled="busy.reviews">刷新报告</button>
          </div>

          <div class="section-title">PR 审查报告</div>
          <div v-if="!prReports.length" class="empty"><div class="ico" aria-hidden="true">▦</div><p>这个 PR 暂无报告</p></div>
          <div v-else class="list pr-report-list">
            <div v-for="r in prReports" :key="r.reportId" class="list-row row-reports" :class="{ selected: prActionForm.reportId === r.reportId }" @click="selectPrReport(r.reportId)">
              <span class="mono">#{{ r.reportId }}</span>
              <span class="grow">{{ r.issueCount }} 个问题</span>
              <span class="badge" :class="'risk-' + r.overallRisk">{{ r.overallRisk }}</span>
              <button class="sm secondary" @click.stop="openReport(r.reportId)">查看</button>
            </div>
          </div>

          <div class="section-title">管理员动作</div>
          <div class="grid two">
            <label class="field">动作
              <select v-model="prActionForm.actionType">
                <option value="REQUEST_CHANGES">打回修改</option>
                <option value="APPROVE">通过</option>
                <option value="WAIVE">风险豁免</option>
                <option value="COMMENT">仅评论</option>
              </select>
            </label>
            <label class="field">关联报告
              <select v-model.number="prActionForm.reportId" @change="loadActionReport">
                <option :value="null">不关联</option>
                <option v-for="r in prReports" :key="r.reportId" :value="r.reportId">#{{ r.reportId }} · {{ r.overallRisk }} · {{ r.issueCount }} 问题</option>
              </select>
            </label>
          </div>
          <div v-if="actionReportDetail && actionReportDetail.issues.length" class="issue-picker">
            <div class="kb-head">
              <span class="section-title" style="margin:0">关联问题项</span>
              <div class="kb-tools">
                <button class="sm secondary" @click="selectBlockingIssues">选择高/中危</button>
                <button class="sm secondary" @click="clearActionIssues">清空</button>
              </div>
            </div>
            <label v-for="issue in actionReportDetail.issues" :key="issue.issueId" class="issue-check" :class="{ on: actionIssueIds.has(issue.issueId) }">
              <input type="checkbox" :checked="actionIssueIds.has(issue.issueId)" @change="toggleActionIssue(issue.issueId)" />
              <span class="badge" :class="'sev-' + issue.severity">{{ issue.severity }}</span>
              <span class="grow">{{ issue.title }}</span>
            </label>
          </div>
          <label class="field" style="margin-top:16px">原因<textarea v-model="prActionForm.reason" placeholder="例如：存在高风险权限问题，暂不允许合并"></textarea></label>
          <label class="field" style="margin-top:12px">整改要求<textarea v-model="prActionForm.requirement" placeholder="例如：补充权限校验，并新增对应测试"></textarea></label>
          <div class="actions">
            <button @click="run(submitPrAction)" :disabled="busy.prAction">
              <span v-if="busy.prAction" class="spinner"></span>提交动作
            </button>
          </div>

          <div v-if="prActions.length" class="list action-history">
            <div v-for="a in prActions" :key="a.actionId" class="list-row action-row">
              <span class="status-pill" :class="'st-' + actionStateClass(a.actionType)">{{ actionLabel(a.actionType) }}</span>
              <span class="grow">{{ a.reason || a.requirement || '无补充说明' }}</span>
              <span class="mono">{{ fmtTime(a.createdAt) }}</span>
            </div>
          </div>
        </template>
      </div>
    </div>
</template>

<script setup>
import KnowledgeDocPicker from '../components/KnowledgeDocPicker.vue'
import { fmtTime, shortCommit } from '../utils/format.js'
import { prStateLabel, actionLabel, actionStateClass } from '../utils/labels.js'
import { useBusy } from '../composables/useBusy.js'
import { useSession } from '../composables/useSession.js'
import { useRepository } from '../composables/useRepository.js'
import { useReviews } from '../composables/useReviews.js'
import { useKnowledge } from '../composables/useKnowledge.js'
import { usePullRequests } from '../composables/usePullRequests.js'
import { useWorkspace } from '../composables/useWorkspace.js'

const { busy, run } = useBusy()
const { activeProject } = useSession()
const { selectedCommit } = useRepository()
const { loadReviews } = useReviews()
const { documents, prDocs } = useKnowledge()
const { pullRequestForm, prActionForm, pullRequests, activePullRequest, prActions, actionReportDetail, actionIssueIds, prReports, resetPullRequestForm, savePullRequest, loadPullRequests, selectPullRequest, editPullRequest, createPrReview, selectPrReport, loadActionReport, toggleActionIssue, selectBlockingIssues, clearActionIssues, submitPrAction } = usePullRequests()
const { fillPrFromSelectedCommit, openReport } = useWorkspace()
</script>
