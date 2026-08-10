<template>
  <div class="view">
    <el-card class="form-card" shadow="never">
      <template #header>
        <div class="card-head">
          <div><h2>{{ pullRequestForm.pullRequestId ? '更新 PR' : '登记 PR' }}</h2><div class="card-sub">把真实团队里的 Pull Request 纳入审查闭环</div></div>
          <div class="head-actions">
            <el-button v-if="pullRequestForm.pullRequestId" @click="resetPullRequestForm">取消编辑</el-button>
            <el-button :disabled="!selectedCommit" @click="fillPrFromSelectedCommit">使用已选 Commit</el-button>
          </div>
        </div>
      </template>
      <el-form label-position="top" @submit.prevent>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="6"><el-form-item label="PR 编号"><el-input v-model.number="pullRequestForm.prNumber" type="number" min="1" placeholder="123" /></el-form-item></el-col>
          <el-col :xs="24" :sm="6">
            <el-form-item label="Provider">
              <el-select v-model="pullRequestForm.provider">
                <el-option label="GitHub" value="GITHUB" />
                <el-option label="GitLab" value="GITLAB" />
                <el-option label="Gitee" value="GITEE" />
                <el-option label="其他" value="OTHER" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12"><el-form-item label="标题"><el-input v-model="pullRequestForm.title" placeholder="feat: add order review gate" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="6"><el-form-item label="作者"><el-input v-model="pullRequestForm.authorName" placeholder="developer" /></el-form-item></el-col>
          <el-col :xs="24" :sm="6"><el-form-item label="源分支"><el-input v-model="pullRequestForm.sourceBranch" placeholder="feature/order-gate" /></el-form-item></el-col>
          <el-col :xs="24" :sm="6"><el-form-item label="目标分支"><el-input v-model="pullRequestForm.targetBranch" :placeholder="activeProject ? activeProject.defaultBranch : 'main'" /></el-form-item></el-col>
          <el-col :xs="24" :sm="6"><el-form-item label="外部 PR ID"><el-input v-model="pullRequestForm.externalPrId" placeholder="可选" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="12"><el-form-item label="Base SHA / Ref"><el-input v-model="pullRequestForm.baseSha" placeholder="main 或 base commit" /></el-form-item></el-col>
          <el-col :xs="24" :sm="12"><el-form-item label="Head SHA / Ref"><el-input v-model="pullRequestForm.headSha" placeholder="feature 分支或 head commit" /></el-form-item></el-col>
        </el-row>
      </el-form>
      <el-space wrap :size="12">
        <el-button type="primary" :loading="busy.pullRequest" :disabled="busy.pullRequest" @click="run(savePullRequest)">{{ pullRequestForm.pullRequestId ? '保存 PR 更新' : '登记 PR' }}</el-button>
        <el-button :disabled="busy.pullRequests" @click="run(loadPullRequests)">刷新 PR</el-button>
      </el-space>
    </el-card>

    <div class="split">
      <el-card shadow="never">
        <template #header>
          <div class="card-head"><div><h2>PR 列表</h2><div class="card-sub">{{ pullRequests.length }} 条</div></div></div>
        </template>
        <el-skeleton v-if="busy.pullRequests && !pullRequests.length" :rows="3" animated />
        <el-empty v-else-if="!pullRequests.length" description="暂无 PR" :image-size="88"><p class="empty-extra">先登记一个 PR，再触发审查。</p></el-empty>
        <el-table
          v-else
          class="rs-prs"
          :data="pullRequests"
          row-key="pullRequestId"
          highlight-current-row
          :current-row-key="activePullRequest ? activePullRequest.pullRequestId : null"
          :row-style="{ cursor: 'pointer' }"
          @row-click="row => selectPullRequest(row)"
        >
          <el-table-column label="编号" width="84">
            <template #default="{ row }"><span class="mono">#{{ row.prNumber || row.pullRequestId }}</span></template>
          </el-table-column>
          <el-table-column label="标题" prop="title" min-width="96" show-overflow-tooltip />
          <el-table-column label="状态" width="100">
            <template #default="{ row }"><span class="status-pill" :class="'st-' + row.reviewState">{{ prStateLabel(row.reviewState) }}</span></template>
          </el-table-column>
          <el-table-column label="Head" width="92">
            <template #default="{ row }"><span class="mono">{{ shortCommit(row.headSha) }}</span></template>
          </el-table-column>
          <el-table-column label="操作" width="108" align="right">
            <template #default="{ row }"><el-button size="small" title="打开该 PR 对应的 Agent Run" @click.stop="openAgentRunForPr(row)">Agent Run</el-button></template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <div class="card-head">
            <div><h2>PR 审查闭环</h2><div class="card-sub">{{ activePullRequest ? activePullRequest.title : '请选择 PR' }}</div></div>
            <el-button v-if="activePullRequest" @click="editPullRequest(activePullRequest)">编辑 PR</el-button>
          </div>
        </template>
        <el-empty v-if="!activePullRequest" description="选择一个 PR" :image-size="88" />
        <template v-else>
          <div class="pr-meta">
            <span class="badge plain">{{ activePullRequest.provider }}</span>
            <span class="status-pill" :class="'st-' + activePullRequest.status">{{ activePullRequest.status }}</span>
            <span class="mono">{{ activePullRequest.sourceBranch }} → {{ activePullRequest.targetBranch }}</span>
          </div>

          <KnowledgeDocPicker v-model="prDocs" :documents="documents" compact
            title="参与 PR 审查的知识库"
            empty-hint="该项目暂无知识库文档；不选则审查仅基于 PR diff。" />

          <el-space wrap :size="12" class="rs-actions">
            <el-button type="primary" :loading="busy.prReview" :disabled="busy.prReview" @click="run(createPrReview)">触发 PR 审查</el-button>
            <el-button :disabled="busy.reviews" @click="run(loadReviews)">刷新报告</el-button>
          </el-space>

          <div class="section-title">PR 审查报告</div>
          <el-empty v-if="!prReports.length" description="这个 PR 暂无报告" :image-size="64" />
          <div v-else class="list pr-report-list">
            <div v-for="r in prReports" :key="r.reportId" class="list-row row-reports" :class="{ selected: prActionForm.reportId === r.reportId }" @click="selectPrReport(r.reportId)">
              <span class="mono">#{{ r.reportId }}</span>
              <span class="grow">{{ r.issueCount }} 个问题</span>
              <span class="badge" :class="'risk-' + r.overallRisk">{{ r.overallRisk }}</span>
              <el-button size="small" @click.stop="openReport(r.reportId)">查看</el-button>
            </div>
          </div>

          <div class="section-title">管理员动作</div>
          <el-form label-position="top" @submit.prevent>
            <el-row :gutter="16">
              <el-col :xs="24" :sm="12">
                <el-form-item label="动作">
                  <el-select v-model="prActionForm.actionType">
                    <el-option label="打回修改" value="REQUEST_CHANGES" />
                    <el-option label="通过" value="APPROVE" />
                    <el-option label="风险豁免" value="WAIVE" />
                    <el-option label="仅评论" value="COMMENT" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="12">
                <el-form-item label="关联报告">
                  <el-select v-model="prActionForm.reportId" placeholder="不关联" @change="loadActionReport">
                    <el-option label="不关联" :value="null" />
                    <el-option v-for="r in prReports" :key="r.reportId" :value="r.reportId" :label="`#${r.reportId} · ${r.overallRisk} · ${r.issueCount} 问题`" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
            <div v-if="actionReportDetail && actionReportDetail.issues.length" class="issue-picker">
              <div class="kb-head">
                <span class="section-title" style="margin:0">关联问题项</span>
                <div class="kb-tools">
                  <el-button size="small" @click="selectBlockingIssues">选择高/中危</el-button>
                  <el-button size="small" @click="clearActionIssues">清空</el-button>
                </div>
              </div>
              <label v-for="issue in actionReportDetail.issues" :key="issue.issueId" class="issue-check" :class="{ on: actionIssueIds.has(issue.issueId) }">
                <input type="checkbox" :checked="actionIssueIds.has(issue.issueId)" @change="toggleActionIssue(issue.issueId)" />
                <span class="badge" :class="'sev-' + issue.severity">{{ issue.severity }}</span>
                <span class="grow">{{ issue.title }}</span>
              </label>
            </div>
            <el-form-item label="原因"><el-input v-model="prActionForm.reason" type="textarea" :rows="3" placeholder="例如：存在高风险权限问题，暂不允许合并" /></el-form-item>
            <el-form-item label="整改要求"><el-input v-model="prActionForm.requirement" type="textarea" :rows="3" placeholder="例如：补充权限校验，并新增对应测试" /></el-form-item>
          </el-form>
          <el-space wrap :size="12">
            <el-button type="primary" :loading="busy.prAction" :disabled="busy.prAction" @click="run(submitPrAction)">提交动作</el-button>
          </el-space>

          <div v-if="prActions.length" class="list action-history">
            <div v-for="a in prActions" :key="a.actionId" class="list-row action-row">
              <span class="status-pill" :class="'st-' + actionStateClass(a.actionType)">{{ actionLabel(a.actionType) }}</span>
              <span class="grow">{{ a.reason || a.requirement || '无补充说明' }}</span>
              <span class="mono">{{ fmtTime(a.createdAt) }}</span>
            </div>
          </div>
        </template>
      </el-card>
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
const { fillPrFromSelectedCommit, openReport, openAgentRunForPr } = useWorkspace()
</script>

<style scoped>
/* Precision Workbench · PR 工作流。Element 结构 + tokens 直供。
   scoped 同名重定义清单(styles.css 冻结期间由本段供视觉,styles.css
   删除后本页不受影响):.split、.pr-meta、.section-title、.pr-report-list、
   .list/.list-row(.selected/.grow/.mono)/.row-reports、.issue-picker、
   .kb-head/.kb-tools(issue-picker 页内实例)、.issue-check(.on)、
   .action-history/.action-row、.badge(.plain、.risk-*、.sev-*)、
   .status-pill(.st-* 全族;RUNNING/PENDING 脉冲动画由全局 keyframes
   现供、tokens.css @layer 接管,与 AiLogsView 同法)、.mono */
.view {
  font-family: var(--rs-font-body);
  color: var(--rs-text);
}

.form-card { margin-bottom: var(--sp-5); }

/* ---- 卡头(与 ProjectsView 同构;h2 显式定字族,躲开 styles.css
   元素级 --font-display 泄漏) ---- */
.card-head { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); flex-wrap: wrap; }
.card-head h2 { margin: 0; font-family: var(--rs-font-body); font-size: var(--rs-fs-lg); font-weight: 700; color: var(--rs-text); }
.card-sub { margin-top: 3px; color: var(--rs-text-dim); font-size: var(--rs-fs-sm); font-weight: 400; }
.head-actions { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.head-actions .el-button + .el-button { margin-left: 0; }

.empty-extra { margin: 0; color: var(--rs-text-dim); font-size: var(--rs-fs-sm); }

/* ---- 左右双栏(同名重定义;960 以下折单栏,与原全局断点一致) ---- */
.split { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-4); }

/* ---- PR 表格:选中行走 Element 官方变量(current-row-key 由
   activePullRequest 声明式驱动,外部选中与行点击同步高亮) ---- */
.rs-prs {
  --el-table-text-color: var(--rs-text);
  --el-table-current-row-bg-color: var(--el-color-primary-light-9);
}
.rs-prs .mono { font-size: var(--rs-fs-sm); color: var(--rs-text-soft); }

.mono { font-family: var(--rs-font-mono); font-feature-settings: "liga" 0; }

/* ---- PR 元信息条(tokens 重铸) ---- */
.pr-meta {
  display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap;
  padding: var(--sp-3);
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-sm);
  background: var(--rs-fill-lighter);
  margin-bottom: var(--sp-4);
}
.pr-meta .mono { color: var(--rs-text-soft); }

.rs-actions { margin: var(--sp-5) 0; }

/* ---- 分区小标题 ---- */
.section-title {
  margin: var(--sp-1) 0 var(--sp-3);
  font-size: var(--rs-fs-xs);
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.09em;
  color: var(--rs-text-dim);
}

/* ---- 报告列表 / 动作历史(非表格小列表,保留语义结构 tokens 重铸) ---- */
.pr-report-list { margin: var(--sp-3) 0 var(--sp-5); }
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
/* 与全局 .list-row:hover:not(:disabled) 同形取更高特异性,顺带清掉
   Observatory 的位移/阴影动效 */
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
.row-reports { grid-template-columns: 58px 1fr auto auto; }
.action-history { margin-top: var(--sp-4); }
.action-row { grid-template-columns: 120px 1fr 82px; cursor: default; }

/* ---- 关联问题勾选清单(保留自定义 checkbox 行结构,保真优先) ---- */
.issue-picker {
  margin-bottom: var(--sp-4);
  padding: var(--sp-4);
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-sm);
  background: var(--rs-fill-lighter);
  display: grid; gap: var(--sp-2);
}
.kb-head { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); flex-wrap: wrap; }
.kb-tools { display: flex; gap: var(--sp-2); }
.kb-tools .el-button + .el-button { margin-left: 0; }

.issue-check {
  display: flex; align-items: center; gap: var(--sp-2);
  padding: 8px 10px;
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-sm);
  background: var(--rs-surface);
  color: var(--rs-text-soft);
  cursor: pointer;
  transition: border-color var(--rs-t-fast) var(--rs-ease-out), background var(--rs-t-fast), color var(--rs-t-fast);
}
.issue-check:hover { border-color: var(--el-color-primary-light-3); color: var(--rs-text); }
.issue-check.on { border-color: var(--rs-primary); background: var(--el-color-primary-light-9); }
.issue-check input {
  width: auto; min-height: 0; margin: 0; padding: 0; flex: none;
  accent-color: var(--rs-primary);
}
.issue-check input:focus-visible { outline: 2px solid var(--rs-primary); outline-offset: 2px; }
.issue-check .grow { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ---- 徽标(badge 同名重定义:去 Observatory 辉光,补 1px 语义描边,
   内距 3px10 → 2px9 保持外框尺寸) ---- */
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
   本页现值:reviewState 待审查/已通过/已打回/已豁免、PR status
   OPEN/MERGED/CLOSED、动作映射 SUCCESS/FAILED/PENDING/CONSUMED,
   整族收录保持全站一致) ---- */
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

/* ---- 窄屏:折单栏 + 小列表降两列(与原全局 960 断点语义一致) ---- */
@media (max-width: 960px) {
  .split { grid-template-columns: 1fr; }
  .row-reports, .action-row { grid-template-columns: 1fr auto; }
}
</style>
