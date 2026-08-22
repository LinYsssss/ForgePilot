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
import { listProjects, type Project } from "../project/api";
import { listRequirements, type RequirementSummary } from "../requirement/api";
import { REVIEW_ACTIVITY_LABELS } from "../requirement/status";
import { getPullRequest, type PullRequest } from "../scm/api";
import {
  listPullRequestReviews,
  listReviewActivity,
  requestReview,
  type ActivityView,
  type ReviewSummary,
} from "./api";
import {
  shortSha,
  PULL_REQUEST_ACTIVITIES,
  PULL_REQUEST_ACTIVITY_LABELS,
  REVIEW_DECISION_LABELS,
  REVIEW_DECISION_TONES,
  REVIEW_STATUS_LABELS,
  REVIEW_STATUS_TONES,
} from "./labels";

const route = useRoute();
const router = useRouter();

const projectId = computed(() => parseId(route.query[PROJECT_QUERY_KEY]));
const pullRequestId = computed(() => parseId(route.query[PULL_REQUEST_QUERY_KEY]));

const projects = ref<Project[]>([]);
const requirements = ref<RequirementSummary[]>([]);
const activity = ref<Record<string, ActivityView>>({});
const projectError = ref<string | null>(null);
const projectLoading = ref(false);

const pullRequest = ref<PullRequest | null>(null);
const reviews = ref<ReviewSummary[]>([]);
const reviewsLoading = ref(false);
const reviewsError = ref<string | null>(null);

const pullRequestInput = ref("");
const triggerPending = ref(false);
const triggerMessage = ref<string | null>(null);

const projectSelection = computed({
  get: () => projectId.value,
  set: (value: number | null) => {
    if (value !== null) {
      void router.push(reviewsRoute(value));
    }
  },
});

/** Newest first. The server returns oldest first by `(created_at, id)`. */
const orderedReviews = computed(() =>
  [...reviews.value].sort((left, right) =>
    left.createdAt === right.createdAt
      ? right.id - left.id
      : right.createdAt.localeCompare(left.createdAt),
  ),
);

function requirementTitle(requirementId: number | null): string {
  if (requirementId === null) {
    return "未关联需求";
  }
  const match = requirements.value.find((item) => item.id === requirementId);
  return match === undefined ? `需求 ${requirementId}` : match.title;
}

interface ActivityRow {
  requirement: RequirementSummary;
  /** Null when the map came back without this requirement, which is not the same as zero. */
  activity: ActivityView | null;
}

const activityRows = computed<ActivityRow[]>(() =>
  requirements.value.map((requirement) => ({
    requirement,
    activity: activity.value[String(requirement.id)] ?? null,
  })),
);

watch(
  projectId,
  async (id) => {
    requirements.value = [];
    activity.value = {};
    projectError.value = null;
    if (id === null) {
      return;
    }
    projectLoading.value = true;
    try {
      const [loadedRequirements, loadedActivity] = await Promise.all([
        listRequirements(id),
        listReviewActivity(id),
      ]);
      requirements.value = loadedRequirements;
      activity.value = loadedActivity;
    } catch (failure: unknown) {
      projectError.value = apiErrorMessage(failure);
    } finally {
      projectLoading.value = false;
    }
  },
  { immediate: true },
);

watch(
  [projectId, pullRequestId],
  async ([project, pull]) => {
    pullRequest.value = null;
    reviews.value = [];
    reviewsError.value = null;
    triggerMessage.value = null;
    if (project === null || pull === null) {
      return;
    }
    pullRequestInput.value = String(pull);
    reviewsLoading.value = true;
    try {
      const [loadedPullRequest, loadedReviews] = await Promise.all([
        getPullRequest(project, pull),
        listPullRequestReviews(project, pull),
      ]);
      pullRequest.value = loadedPullRequest;
      reviews.value = loadedReviews;
    } catch (failure: unknown) {
      reviewsError.value = apiErrorMessage(failure);
    } finally {
      reviewsLoading.value = false;
    }
  },
  { immediate: true },
);

function openPullRequest(): void {
  const project = projectId.value;
  const pull = parseId(pullRequestInput.value);
  if (project === null || pull === null) {
    reviewsError.value = "请输入一个正整数 PR 记录 id。";
    return;
  }
  void router.push(reviewsRoute(project, pull));
}

async function triggerReview(): Promise<void> {
  const project = projectId.value;
  const pull = pullRequestId.value;
  if (project === null || pull === null) {
    return;
  }
  triggerPending.value = true;
  triggerMessage.value = null;
  reviewsError.value = null;
  try {
    const requested = await requestReview(project, pull);
    triggerMessage.value = `已受理：审查 ${requested.reviewId}，执行状态 ${
      REVIEW_STATUS_LABELS[requested.status]
    }，第 ${requested.executionAttempt} 次执行。`;
    reviews.value = await listPullRequestReviews(project, pull);
  } catch (failure: unknown) {
    reviewsError.value = apiErrorMessage(failure);
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
  <section aria-labelledby="reviews-title">
    <div class="page-head">
      <p class="eyebrow">Review</p>
      <h1 id="reviews-title">代码审查</h1>
    </div>

    <div class="panel inline-form">
      <div class="field">
        <label for="review-project">当前项目</label>
        <select id="review-project" v-model="projectSelection">
          <option :value="null" disabled>请选择项目</option>
          <option v-for="project in projects" :key="project.id" :value="project.id">
            {{ project.name }}
          </option>
        </select>
      </div>
    </div>

    <p v-if="projectId === null" class="empty-state">先选择一个项目，再查看它的审查记录。</p>

    <template v-else>
      <form class="panel inline-form" @submit.prevent="openPullRequest">
        <div class="field">
          <label for="review-pull-request">PR 记录 id</label>
          <input
            id="review-pull-request"
            v-model="pullRequestInput"
            type="number"
            min="1"
            step="1"
            inputmode="numeric"
          />
          <p class="field-hint">
            服务端没有项目级 Review 列表端点，本页按 PR 检索一条 PR 的全部 Review。
          </p>
        </div>
        <button type="submit" class="button button-primary">查看审查记录</button>
      </form>

      <p v-if="projectError" class="alert" role="alert">{{ projectError }}</p>

      <section class="panel" aria-labelledby="pull-request-reviews-title">
        <h2 id="pull-request-reviews-title" class="panel-title">PR 的审查记录</h2>

        <p v-if="pullRequestId === null" class="empty-state">
          还没有选定 PR。填入 PR 记录 id 后可以看到它的全部 Review。
        </p>
        <p v-else-if="reviewsLoading" class="muted">正在加载审查记录…</p>
        <template v-else>
          <p v-if="reviewsError" class="alert" role="alert">{{ reviewsError }}</p>

          <dl v-if="pullRequest" class="meta-list pull-request-head">
            <div>
              <dt>PR 编号</dt>
              <dd class="pull-request-number">PR #{{ pullRequest.externalNumber }}</dd>
            </div>
            <div>
              <dt>当前 head SHA</dt>
              <dd>
                <code :title="pullRequest.headSha">{{ shortSha(pullRequest.headSha) }}</code>
              </dd>
            </div>
            <div>
              <dt>关联需求</dt>
              <dd>
                <RouterLink
                  v-if="pullRequest.requirementId !== null && projectId !== null"
                  :to="requirementDetailRoute(projectId, pullRequest.requirementId)"
                >
                  {{ requirementTitle(pullRequest.requirementId) }}
                </RouterLink>
                <span v-else>未关联需求</span>
              </dd>
            </div>
            <div>
              <dt>作者</dt>
              <dd>{{ pullRequest.authorUsername ?? "未知" }}</dd>
            </div>
          </dl>

          <div v-if="pullRequest" class="form-actions trigger-actions">
            <button
              type="button"
              class="button"
              :disabled="triggerPending"
              @click="triggerReview"
            >
              对该 PR 请求一次审查
            </button>
            <p class="field-hint">
              同一身份四元组已存在时是幂等返回，失败的会复用同一行重跑，已完成的会被拒绝。
            </p>
          </div>
          <p v-if="triggerMessage" class="muted trigger-message">{{ triggerMessage }}</p>

          <p v-if="orderedReviews.length === 0 && !reviewsError" class="empty-state">
            该 PR 还没有任何 Review。
          </p>

          <div v-else-if="orderedReviews.length > 0" class="table-scroll">
            <table class="data-table review-table">
              <caption class="table-caption">
                按创建时间倒序，全部 Review 一次列出（MVP 不分页）。
              </caption>
              <thead>
                <tr>
                  <th scope="col">审查</th>
                  <th scope="col">PR 编号</th>
                  <th scope="col">head SHA</th>
                  <th scope="col">关联需求</th>
                  <th scope="col">执行状态</th>
                  <th scope="col">Decision</th>
                  <th scope="col">是否当前有效</th>
                  <th scope="col">创建时间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="review in orderedReviews" :key="review.id" class="review-row">
                  <td>
                    <RouterLink
                      v-if="projectId !== null"
                      :to="reviewDetailRoute(projectId, review.id)"
                    >
                      审查 {{ review.id }}
                    </RouterLink>
                  </td>
                  <td>PR #{{ pullRequest ? pullRequest.externalNumber : "—" }}</td>
                  <td>
                    <code :title="review.headSha">{{ shortSha(review.headSha) }}</code>
                  </td>
                  <td>
                    {{
                      review.requirementRevisionId === null
                        ? "未关联需求"
                        : `需求版本 ${review.requirementRevisionId}`
                    }}
                  </td>
                  <td class="review-status">
                    <span :class="['badge', `badge-${REVIEW_STATUS_TONES[review.status]}`]">
                      {{ REVIEW_STATUS_LABELS[review.status] }}
                    </span>
                  </td>
                  <td class="review-decision">
                    <span :class="['badge', `badge-${REVIEW_DECISION_TONES[review.decision]}`]">
                      {{ REVIEW_DECISION_LABELS[review.decision] }}
                    </span>
                  </td>
                  <td class="review-current">
                    {{ review.isCurrent ? "当前有效" : "已过期" }}
                  </td>
                  <td>{{ formatDateTime(review.createdAt) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </section>

      <section class="panel" aria-labelledby="requirement-activity-title">
        <h2 id="requirement-activity-title" class="panel-title">需求的评审活动</h2>
        <p class="field-hint">
          评审活动是只读派生量，与需求状态是两个维度，不合并展示。
        </p>

        <p v-if="projectLoading" class="muted">正在加载评审活动…</p>
        <p v-else-if="requirements.length === 0" class="empty-state">该项目还没有需求。</p>

        <ul v-else class="record-list">
          <li v-for="row in activityRows" :key="row.requirement.id" class="record">
            <div class="record-head">
              <h3 class="record-title">
                <RouterLink
                  v-if="projectId !== null"
                  :to="requirementDetailRoute(projectId, row.requirement.id)"
                >
                  {{ row.requirement.title }}
                </RouterLink>
              </h3>
              <span class="badge badge-neutral">v{{ row.requirement.currentRevisionSeq }}</span>
            </div>

            <dl class="meta-list">
              <div>
                <dt>评审活动</dt>
                <dd class="review-activity">
                  <span class="badge badge-info">
                    {{
                      row.activity === null
                        ? "未返回"
                        : REVIEW_ACTIVITY_LABELS[row.activity.activity]
                    }}
                  </span>
                </dd>
              </div>
              <div v-for="state in PULL_REQUEST_ACTIVITIES" :key="state">
                <dt>{{ PULL_REQUEST_ACTIVITY_LABELS[state] }}</dt>
                <dd>{{ row.activity === null ? 0 : row.activity.counts[state] }} 个 PR</dd>
              </div>
            </dl>
          </li>
        </ul>
      </section>
    </template>
  </section>
</template>

<style scoped>
.pull-request-head {
  margin-bottom: var(--fp-space-4);
}

.trigger-actions {
  margin-bottom: var(--fp-space-4);
}

.trigger-message {
  margin-bottom: var(--fp-space-4);
}

/* Bounded local scroll: a wide table never gives the page horizontal overflow. */
.table-scroll {
  overflow-x: auto;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
}

.table-caption {
  margin-bottom: var(--fp-space-3);
  color: var(--fp-color-text-muted);
  font-size: 0.8125rem;
  text-align: left;
}

.data-table th,
.data-table td {
  padding: var(--fp-space-2) var(--fp-space-3);
  border-bottom: 0.0625rem solid var(--fp-color-border);
  vertical-align: top;
  white-space: nowrap;
}

.data-table th {
  color: var(--fp-color-text-muted);
  font-size: 0.8125rem;
}
</style>
