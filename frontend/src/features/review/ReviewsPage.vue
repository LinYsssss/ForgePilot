<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";

import {
  parseId,
  requirementDetailRoute,
  reviewDetailRoute,
  reviewsRoute,
  PROJECT_QUERY_KEY,
  PULL_REQUEST_QUERY_KEY,
} from "../../app/routes";
import { formatDateTime } from "../../lib/datetime";
import { apiErrorMessage } from "../../lib/http";
import { useSession } from "../auth/session";
import { hasProjectRole, listProjects, type Project } from "../project/api";
import { listRequirements, type RequirementSummary } from "../requirement/api";
import { getPullRequest, type PullRequest } from "../scm/api";
import {
  listProjectReviews,
  listPullRequestReviews,
  listReviewActivity,
  requestReview,
  type ActivityView,
  type ProjectReviewRow,
  type ReviewDecision,
  type ReviewStatus,
  type ReviewSummary,
} from "./api";
import {
  shortSha,
  PULL_REQUEST_ACTIVITIES,
  PULL_REQUEST_ACTIVITY_LABELS,
  REVIEW_ACTIVITY_LABELS,
  REVIEW_ACTIVITY_TONES,
  REVIEW_DECISION_LABELS,
  REVIEW_DECISION_TONES,
  REVIEW_STATUS_LABELS,
  REVIEW_STATUS_TONES,
} from "./labels";

const route = useRoute();
const router = useRouter();
const { account } = useSession();

const projectId = computed(() => parseId(route.query[PROJECT_QUERY_KEY]));
const pullRequestId = computed(() => parseId(route.query[PULL_REQUEST_QUERY_KEY]));

const projects = ref<Project[]>([]);
const requirements = ref<RequirementSummary[]>([]);
const projectReviews = ref<ProjectReviewRow[]>([]);
const activity = ref<Record<string, ActivityView>>({});
const projectLoading = ref(false);
const projectError = ref<string | null>(null);

const pullRequest = ref<PullRequest | null>(null);
const pullRequestReviews = ref<ReviewSummary[]>([]);
const pullRequestLoading = ref(false);
const pullRequestError = ref<string | null>(null);

const search = ref("");
const statusFilter = ref<ReviewStatus | "">("");
const decisionFilter = ref<ReviewDecision | "">("");
const currentFilter = ref<"" | "current" | "stale">("");
const pullRequestInput = ref("");
const triggerPending = ref(false);
const triggerMessage = ref<string | null>(null);
let projectLoadToken = 0;
let pullRequestLoadToken = 0;

const selectedProject = computed(
  () => projects.value.find((project) => project.id === projectId.value) ?? null,
);

const projectSelection = computed({
  get: () => projectId.value,
  set: (value: number | null) => {
    if (value !== null) {
      void router.push(reviewsRoute(value));
    }
  },
});

const filteredReviews = computed(() => {
  const query = search.value.trim().toLocaleLowerCase();
  return projectReviews.value.filter((review) => {
    const requirement = requirementTitle(review.requirementId).toLocaleLowerCase();
    return (
      (query === "" ||
        String(review.id).includes(query) ||
        String(review.pullRequestNumber).includes(query) ||
        review.headSha.toLocaleLowerCase().includes(query) ||
        requirement.includes(query)) &&
      (statusFilter.value === "" || review.status === statusFilter.value) &&
      (decisionFilter.value === "" || review.decision === decisionFilter.value) &&
      (currentFilter.value === "" ||
        (currentFilter.value === "current" ? review.isCurrent : !review.isCurrent))
    );
  });
});

const orderedPullRequestReviews = computed(() =>
  [...pullRequestReviews.value].sort((left, right) =>
    left.createdAt === right.createdAt
      ? right.id - left.id
      : right.createdAt.localeCompare(left.createdAt),
  ),
);

const activityRows = computed(() =>
  requirements.value.map((requirement) => ({
    requirement,
    activity: activity.value[String(requirement.id)] ?? null,
  })),
);

const canTriggerReview = computed(() => {
  return (
    hasProjectRole(selectedProject.value, "LEADER") ||
    hasProjectRole(selectedProject.value, "REVIEWER") ||
    (hasProjectRole(selectedProject.value, "DEVELOPER") &&
      pullRequest.value?.authorUserId === account.value?.id)
  );
});

function requirementTitle(requirementId: number | null): string {
  if (requirementId === null) {
    return "未关联需求";
  }
  return requirements.value.find((item) => item.id === requirementId)?.title ?? `需求 ${requirementId}`;
}

watch(
  projectId,
  async (id) => {
    const token = ++projectLoadToken;
    requirements.value = [];
    projectReviews.value = [];
    activity.value = {};
    projectError.value = null;
    if (id === null) {
      return;
    }
    projectLoading.value = true;
    try {
      const [loadedRequirements, loadedReviews, loadedActivity] = await Promise.all([
        listRequirements(id),
        listProjectReviews(id),
        listReviewActivity(id),
      ]);
      if (token !== projectLoadToken) {
        return;
      }
      requirements.value = loadedRequirements;
      projectReviews.value = loadedReviews;
      activity.value = loadedActivity;
    } catch (failure: unknown) {
      if (token === projectLoadToken) {
        projectError.value = apiErrorMessage(failure);
      }
    } finally {
      if (token === projectLoadToken) {
        projectLoading.value = false;
      }
    }
  },
  { immediate: true },
);

watch(
  [projectId, pullRequestId],
  async ([project, pull]) => {
    const token = ++pullRequestLoadToken;
    pullRequest.value = null;
    pullRequestReviews.value = [];
    pullRequestError.value = null;
    triggerMessage.value = null;
    if (project === null || pull === null) {
      return;
    }
    pullRequestInput.value = String(pull);
    pullRequestLoading.value = true;
    try {
      const [loadedPullRequest, loadedReviews] = await Promise.all([
        getPullRequest(project, pull),
        listPullRequestReviews(project, pull),
      ]);
      if (token !== pullRequestLoadToken) {
        return;
      }
      pullRequest.value = loadedPullRequest;
      pullRequestReviews.value = loadedReviews;
    } catch (failure: unknown) {
      if (token === pullRequestLoadToken) {
        pullRequestError.value = apiErrorMessage(failure);
      }
    } finally {
      if (token === pullRequestLoadToken) {
        pullRequestLoading.value = false;
      }
    }
  },
  { immediate: true },
);

function openPullRequest(): void {
  const project = projectId.value;
  const pull = parseId(pullRequestInput.value);
  if (project === null || pull === null) {
    pullRequestError.value = "请输入一个正整数 PR 记录 id。";
    return;
  }
  void router.push(reviewsRoute(project, pull));
}

async function triggerReview(): Promise<void> {
  const project = projectId.value;
  const pull = pullRequestId.value;
  if (project === null || pull === null || !canTriggerReview.value) {
    return;
  }
  triggerPending.value = true;
  triggerMessage.value = null;
  pullRequestError.value = null;
  try {
    const requested = await requestReview(project, pull);
    triggerMessage.value = `已受理审查 ${requested.reviewId}：${REVIEW_STATUS_LABELS[requested.status]}，第 ${requested.executionAttempt} 次执行。`;
    [pullRequestReviews.value, projectReviews.value] = await Promise.all([
      listPullRequestReviews(project, pull),
      listProjectReviews(project),
    ]);
  } catch (failure: unknown) {
    pullRequestError.value = apiErrorMessage(failure);
  } finally {
    triggerPending.value = false;
  }
}

onMounted(async () => {
  try {
    projects.value = await listProjects();
  } catch (failure: unknown) {
    projectError.value = apiErrorMessage(failure);
  }
});
</script>

<template>
  <section class="reviews-page" aria-labelledby="reviews-title">
    <div class="page-head">
      <p class="eyebrow">Review pipeline</p>
      <h1 id="reviews-title">代码审查</h1>
      <p class="lede">唯一 AI Review Engine 基于 Requirement、AC、向量语义召回知识与 Diff 生成证据；执行状态、Finding 人工生命周期、Decision 与当前有效性始终分开呈现。</p>
    </div>

    <div class="panel project-selector">
      <div class="selector-copy">
        <h2 class="panel-title">项目上下文</h2>
        <p class="field-hint">审查、PR 与 Finding 查询始终保持项目隔离。</p>
      </div>
      <div class="field">
        <label for="review-project">当前项目</label>
        <select id="review-project" v-model="projectSelection">
          <option :value="null" disabled>请选择项目</option>
          <option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option>
        </select>
      </div>
    </div>

    <p v-if="projectId === null" class="empty-state">先选择一个项目，再查看它的审查记录。</p>

    <template v-else>
      <p v-if="projectError" class="alert" role="alert">{{ projectError }}</p>

      <section class="panel review-index-panel" aria-labelledby="project-reviews-title">
        <div class="index-head">
          <div><p class="eyebrow">Project review index</p><h2 id="project-reviews-title" class="panel-title">项目审查记录</h2></div>
          <span class="badge badge-neutral">{{ projectReviews.length }} 条</span>
        </div>

        <div class="review-filters" aria-label="筛选审查记录">
          <div class="field"><label for="review-search">搜索</label><input id="review-search" v-model="search" type="search" placeholder="审查、PR、需求或 SHA" /></div>
          <div class="field">
            <label for="review-status-filter">执行状态</label>
            <select id="review-status-filter" v-model="statusFilter"><option value="">全部</option><option v-for="(_, status) in REVIEW_STATUS_LABELS" :key="status" :value="status">{{ REVIEW_STATUS_LABELS[status] }}</option></select>
          </div>
          <div class="field">
            <label for="review-decision-filter">Decision</label>
            <select id="review-decision-filter" v-model="decisionFilter"><option value="">全部</option><option v-for="(_, decision) in REVIEW_DECISION_LABELS" :key="decision" :value="decision">{{ REVIEW_DECISION_LABELS[decision] }}</option></select>
          </div>
          <div class="field">
            <label for="review-current-filter">当前有效性</label>
            <select id="review-current-filter" v-model="currentFilter"><option value="">全部</option><option value="current">当前有效</option><option value="stale">已过期</option></select>
          </div>
        </div>

        <p v-if="projectLoading" class="muted">正在加载项目审查记录…</p>
        <p v-else-if="projectReviews.length === 0" class="empty-state">该项目还没有 Review。</p>
        <p v-else-if="filteredReviews.length === 0" class="empty-state">没有符合当前筛选条件的 Review。</p>

        <div v-else class="table-scroll">
          <table class="data-table project-review-table">
            <caption class="table-caption">按服务端返回的时间倒序展示项目内全部 Review。</caption>
            <thead><tr><th scope="col">审查</th><th scope="col">PR</th><th scope="col">关联需求</th><th scope="col">执行状态</th><th scope="col">Decision</th><th scope="col">当前有效性</th><th scope="col">head SHA</th><th scope="col">创建时间</th></tr></thead>
            <tbody>
              <tr v-for="review in filteredReviews" :key="review.id">
                <td><RouterLink :to="reviewDetailRoute(projectId, review.id)">审查 {{ review.id }}</RouterLink></td>
                <td><RouterLink :to="reviewsRoute(projectId, review.pullRequestId)">PR #{{ review.pullRequestNumber }}</RouterLink></td>
                <td><RouterLink v-if="review.requirementId !== null" :to="requirementDetailRoute(projectId, review.requirementId)">{{ requirementTitle(review.requirementId) }}</RouterLink><span v-else>未关联需求</span></td>
                <td><span :class="['badge', `badge-${REVIEW_STATUS_TONES[review.status]}`]">{{ REVIEW_STATUS_LABELS[review.status] }}</span></td>
                <td><span :class="['badge', `badge-${REVIEW_DECISION_TONES[review.decision]}`]">{{ REVIEW_DECISION_LABELS[review.decision] }}</span></td>
                <td><span :class="['badge', review.isCurrent ? 'badge-success' : 'badge-warning']">{{ review.isCurrent ? "当前有效" : "已过期" }}</span></td>
                <td><code :title="review.headSha">{{ shortSha(review.headSha) }}</code></td>
                <td>{{ formatDateTime(review.createdAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <details class="panel activity-overview">
        <summary>需求评审活动概览（{{ activityRows.length }} 个需求）</summary>
        <p class="field-hint">这是关联 PR 的只读派生量，与 Review 执行状态和需求人工状态均不合并。</p>
        <p v-if="activityRows.length === 0" class="empty-state">该项目还没有需求。</p>
        <ul v-else class="activity-list">
          <li v-for="row in activityRows" :key="row.requirement.id">
            <RouterLink :to="requirementDetailRoute(projectId, row.requirement.id)">{{ row.requirement.title }}</RouterLink>
            <span
              v-if="row.activity"
              :class="['badge', `badge-${REVIEW_ACTIVITY_TONES[row.activity.activity]}`]"
            >
              {{ REVIEW_ACTIVITY_LABELS[row.activity.activity] }}
            </span>
            <span v-else class="badge badge-neutral">未返回</span>
            <small v-if="row.activity">
              <span v-for="state in PULL_REQUEST_ACTIVITIES" :key="state">
                {{ PULL_REQUEST_ACTIVITY_LABELS[state] }} {{ row.activity.counts[state] }}
              </span>
            </small>
          </li>
        </ul>
      </details>

      <section v-if="pullRequestId !== null" class="panel pull-request-panel" aria-labelledby="pull-request-history-title">
        <div class="index-head">
          <div><p class="eyebrow">Selected pull request</p><h2 id="pull-request-history-title" class="panel-title">PR 审查历史</h2></div>
          <RouterLink class="button button-quiet" :to="reviewsRoute(projectId)">关闭 PR 视图</RouterLink>
        </div>
        <p v-if="pullRequestLoading" class="muted">正在加载 PR 与审查历史…</p>
        <p v-if="pullRequestError" class="alert" role="alert">{{ pullRequestError }}</p>
        <template v-if="pullRequest">
          <dl class="meta-list pull-request-head">
            <div><dt>PR 编号</dt><dd class="pull-request-number">PR #{{ pullRequest.externalNumber }}</dd></div>
            <div><dt>当前 head</dt><dd><code :title="pullRequest.headSha">{{ shortSha(pullRequest.headSha) }}</code></dd></div>
            <div><dt>关联需求</dt><dd>{{ requirementTitle(pullRequest.requirementId) }}</dd></div>
            <div><dt>作者</dt><dd>{{ pullRequest.authorUsername ?? "未知" }}</dd></div>
          </dl>
          <div class="trigger-row">
            <button v-if="canTriggerReview" type="button" class="button button-primary" :disabled="triggerPending" @click="triggerReview">请求审查 / 重试</button>
            <p v-else class="field-hint">负责人、评审或该 PR 的平台内作者可以请求审查。</p>
            <p class="field-hint">同一审查身份幂等；失败可重试，代码或需求版本变化后可发起新一轮。</p>
          </div>
          <p v-if="triggerMessage" class="muted" role="status">{{ triggerMessage }}</p>
          <p v-if="orderedPullRequestReviews.length === 0" class="empty-state">该 PR 还没有 Review。</p>
          <ol v-else class="history-list">
            <li v-for="review in orderedPullRequestReviews" :key="review.id" class="history-row">
              <RouterLink :to="reviewDetailRoute(projectId, review.id)">审查 {{ review.id }}</RouterLink>
              <code :title="review.headSha">{{ shortSha(review.headSha) }}</code>
              <span :class="['badge', `badge-${REVIEW_STATUS_TONES[review.status]}`]">{{ REVIEW_STATUS_LABELS[review.status] }}</span>
              <span :class="['badge', `badge-${REVIEW_DECISION_TONES[review.decision]}`]">{{ REVIEW_DECISION_LABELS[review.decision] }}</span>
              <span>{{ review.isCurrent ? "当前有效" : "已过期" }}</span>
              <span>{{ formatDateTime(review.createdAt) }}</span>
            </li>
          </ol>
        </template>
      </section>

      <details class="panel recovery-panel">
        <summary>故障恢复：按内部 PR 记录 id 定位</summary>
        <form class="inline-form recovery-form" @submit.prevent="openPullRequest">
          <div class="field"><label for="review-pull-request">PR 记录 id</label><input id="review-pull-request" v-model="pullRequestInput" type="number" min="1" step="1" inputmode="numeric" /></div>
          <button type="submit" class="button button-quiet">打开 PR 历史</button>
          <p class="field-hint">正常使用请直接点击上方列表中的 PR；这里只用于已有内部记录 id 的排障场景。</p>
        </form>
      </details>
    </template>
  </section>
</template>

<style scoped>
.project-selector,
.review-filters {
  display: grid;
  align-items: end;
  gap: var(--fp-space-4);
}

.project-selector { grid-template-columns: minmax(14rem, 1fr) minmax(14rem, 1.2fr); }
.review-filters { grid-template-columns: minmax(15rem, 1.4fr) repeat(3, minmax(10rem, 0.7fr)); margin-bottom: var(--fp-space-5); }
.selector-copy .panel-title,
.index-head .panel-title { margin-bottom: var(--fp-space-1); }
.review-index-panel,
.pull-request-panel { border-color: var(--fp-color-border-accent); }
.index-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--fp-space-4); margin-bottom: var(--fp-space-5); }
.index-head .eyebrow { margin-bottom: var(--fp-space-2); }
.table-scroll { overflow-x: auto; }
.data-table { min-width: 70rem; width: 100%; border-collapse: collapse; text-align: left; }
.table-caption { margin-bottom: var(--fp-space-3); color: var(--fp-color-text-muted); font-size: 0.8125rem; text-align: left; }
.data-table th,
.data-table td { padding: var(--fp-space-3); border-bottom: 0.0625rem solid var(--fp-color-border); vertical-align: top; white-space: nowrap; }
.data-table th { color: var(--fp-color-text-muted); font-size: 0.8125rem; }
.data-table tbody tr:hover { background: var(--fp-color-surface-header-hover); }
.pull-request-head { margin-bottom: var(--fp-space-4); padding: var(--fp-space-4); border: 0.0625rem solid var(--fp-color-border); border-radius: var(--fp-radius-md); background: var(--fp-color-canvas-muted); }
.trigger-row { display: flex; align-items: center; flex-wrap: wrap; gap: var(--fp-space-3); margin-bottom: var(--fp-space-4); }
.history-list { display: grid; margin: var(--fp-space-4) 0 0; padding: 0; list-style: none; }
.history-row { display: grid; grid-template-columns: repeat(6, minmax(0, auto)); align-items: center; gap: var(--fp-space-3); padding: var(--fp-space-3) 0; border-top: 0.0625rem solid var(--fp-color-border); }
.recovery-panel summary { color: var(--fp-color-text-muted); cursor: pointer; font-weight: 700; }
.activity-overview > summary { color: var(--fp-color-text-muted); cursor: pointer; font-weight: 700; }
.activity-overview > .field-hint { margin: var(--fp-space-3) 0; }
.activity-list { display: grid; gap: var(--fp-space-2); margin: 0; padding: 0; list-style: none; }
.activity-list li { display: grid; align-items: center; gap: var(--fp-space-3); padding: var(--fp-space-3); border: 0.0625rem solid var(--fp-color-border); border-radius: var(--fp-radius-sm); grid-template-columns: minmax(12rem, 1fr) auto minmax(0, 2fr); }
.activity-list small { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: var(--fp-space-2); color: var(--fp-color-text-subtle); }
.recovery-form { margin-top: var(--fp-space-4); }

@media (max-width: 64rem) {
  .project-selector,
  .review-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .history-row { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .activity-list li { grid-template-columns: 1fr auto; }
  .activity-list small { grid-column: 1 / -1; justify-content: flex-start; }
}

@media (max-width: 42rem) {
  .project-selector,
  .review-filters,
  .history-row { grid-template-columns: 1fr; }
  .activity-list li { grid-template-columns: 1fr; }
  .activity-list small { grid-column: auto; }
  .index-head { flex-direction: column; }
}
</style>
