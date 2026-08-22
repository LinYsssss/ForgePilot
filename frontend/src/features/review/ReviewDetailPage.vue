<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink, useRoute } from "vue-router";

import {
  parseId,
  requirementDetailRoute,
  reviewsRoute,
  PROJECT_QUERY_KEY,
} from "../../app/routes";
import { formatDateTime } from "../../lib/datetime";
import { apiErrorMessage } from "../../lib/http";
import { getProject, listMembers, type Member, type Project } from "../project/api";
import { listRequirements, type RequirementSummary } from "../requirement/api";
import { getPullRequest, setPullRequestRequirement, type PullRequest } from "../scm/api";
import {
  decideReview,
  getReview,
  listFindingEvents,
  listPullRequestReviews,
  moveFinding,
  type FindingAction,
  type FindingEvent,
  type FindingStatus,
  type ReviewDetail,
  type ReviewSummary,
} from "./api";
import FindingCard from "./FindingCard.vue";
import {
  shortSha,
  AC_VERDICT_LABELS,
  AC_VERDICT_TONES,
  REVIEW_DECISION_LABELS,
  REVIEW_DECISION_TONES,
  REVIEW_STATUS_LABELS,
  REVIEW_STATUS_TONES,
} from "./labels";

const route = useRoute();

const projectId = computed(() => parseId(route.query[PROJECT_QUERY_KEY]));
const reviewId = computed(() => parseId(route.params.id));

const project = ref<Project | null>(null);
const members = ref<Member[]>([]);
const requirements = ref<RequirementSummary[]>([]);
const detail = ref<ReviewDetail | null>(null);
const pullRequest = ref<PullRequest | null>(null);
const history = ref<ReviewSummary[]>([]);

const loading = ref(true);
const loadError = ref<string | null>(null);

const associationSelection = ref<number | null>(null);
const associationReason = ref("");
const associationPending = ref(false);
const associationError = ref<string | null>(null);

const decisionComment = ref("");
const decisionPending = ref(false);
const decisionError = ref<string | null>(null);

const findingPending = ref<number | null>(null);
const findingError = ref<string | null>(null);
const eventsByFinding = ref<Record<string, FindingEvent[]>>({});
const eventsPendingFor = ref<number | null>(null);
const eventsError = ref<{ findingId: number; message: string } | null>(null);

const role = computed(() => project.value?.myRole ?? null);
const isLeader = computed(() => role.value === "LEADER");
const canDecide = computed(() => role.value === "LEADER" || role.value === "REVIEWER");

function target(): { projectId: number; reviewId: number } | null {
  const pid = projectId.value;
  const rid = reviewId.value;
  return pid === null || rid === null ? null : { projectId: pid, reviewId: rid };
}

const hasContext = computed(() => target() !== null);

/**
 * The Decision Gate of ARCHITECTURE.md 3.1: any `REQUEST_CHANGES` on the pull
 * request's *present* head locks that head permanently. It is derived from the
 * rows on every read rather than cached, because a force-push back to a blocked
 * head has to re-lock it and a stored flag would not.
 */
const gateBlocked = computed(() => {
  const head = pullRequest.value?.headSha;
  return (
    head !== undefined &&
    history.value.some(
      (review) => review.headSha === head && review.decision === "REQUEST_CHANGES",
    )
  );
});

/**
 * The six preconditions, evaluated here only to explain a disabled button. The
 * server re-checks all six under a row lock and stays the only authority; this
 * list never grants anything.
 *
 * <p>Preconditions 3, 4 and 5 are read off `isCurrent` rather than recomputed:
 * that flag is the server's own derivation of "head, fingerprint and requirement
 * revision all equal the pull request's present values", and the pull request
 * payload does not even carry its current requirement revision id. Head and
 * fingerprint are visible here, so when one of them explains the mismatch it is
 * named; when neither does, the remaining cause is the requirement revision.
 */
const decisionBlockers = computed<string[]>(() => {
  const review = detail.value;
  const pull = pullRequest.value;
  if (review === null || pull === null) {
    return ["尚未取到 Review 或 PR 数据。"];
  }
  const blockers: string[] = [];
  if (review.status !== "COMPLETED") {
    blockers.push(`执行状态是「${REVIEW_STATUS_LABELS[review.status]}」，只有已完成的 Review 能被决定。`);
  }
  if (review.decision !== "PENDING") {
    blockers.push("这条 Review 已经写入终局决定，终局决定只写一次。");
  }
  if (!review.isCurrent) {
    if (review.headSha !== pull.headSha) {
      blockers.push("PR 的 head SHA 已经推进，这条 Review 审的不是当前代码。");
    } else if (review.reviewInputFingerprint !== pull.reviewInputFingerprint) {
      blockers.push("PR 的审查输入指纹已变化，这条 Review 审的不是当前输入。");
    } else {
      blockers.push("PR 当前指向的需求版本与这条 Review 的需求版本不一致。");
    }
  }
  if (gateBlocked.value) {
    blockers.push("该 head 上已经存在退回，只有新的 head SHA 能解除。");
  }
  return blockers;
});

const canSubmitDecision = computed(
  () => canDecide.value && decisionBlockers.value.length === 0,
);

const contextSnapshotText = computed(() => {
  const snapshot = detail.value?.contextSnapshot;
  return snapshot === null || snapshot === undefined
    ? null
    : JSON.stringify(snapshot, null, 2);
});

function memberName(userId: number | null): string | null {
  if (userId === null) {
    return null;
  }
  const match = members.value.find((member) => member.userId === userId);
  return match === undefined ? `用户 ${userId}` : match.username;
}

function requirementTitle(requirementId: number | null): string {
  if (requirementId === null) {
    return "未关联需求";
  }
  const match = requirements.value.find((item) => item.id === requirementId);
  return match === undefined ? `需求 ${requirementId}` : match.title;
}

async function load(): Promise<void> {
  const ids = target();
  loading.value = true;
  loadError.value = null;
  project.value = null;
  members.value = [];
  requirements.value = [];
  detail.value = null;
  pullRequest.value = null;
  history.value = [];
  eventsByFinding.value = {};
  eventsError.value = null;
  findingError.value = null;
  decisionError.value = null;
  decisionComment.value = "";
  associationError.value = null;
  associationReason.value = "";
  if (ids === null) {
    loading.value = false;
    return;
  }
  try {
    const [loadedProject, loadedMembers, loadedRequirements, loadedReview] =
      await Promise.all([
        getProject(ids.projectId),
        listMembers(ids.projectId),
        listRequirements(ids.projectId),
        getReview(ids.projectId, ids.reviewId),
      ]);
    project.value = loadedProject;
    members.value = loadedMembers;
    requirements.value = loadedRequirements;
    detail.value = loadedReview;

    const [loadedPullRequest, loadedHistory] = await Promise.all([
      getPullRequest(ids.projectId, loadedReview.pullRequestId),
      listPullRequestReviews(ids.projectId, loadedReview.pullRequestId),
    ]);
    pullRequest.value = loadedPullRequest;
    history.value = loadedHistory;
    associationSelection.value = loadedPullRequest.requirementId;
  } catch (failure: unknown) {
    loadError.value = apiErrorMessage(failure);
  } finally {
    loading.value = false;
  }
}

// Watched rather than mounted: vue-router reuses this component when only the
// `:id` changes, so an `onMounted` load would keep showing the previous Review.
watch([projectId, reviewId], load, { immediate: true });

async function saveAssociation(): Promise<void> {
  const ids = target();
  const pull = pullRequest.value;
  if (ids === null || pull === null) {
    return;
  }
  associationPending.value = true;
  associationError.value = null;
  try {
    pullRequest.value = await setPullRequestRequirement(
      ids.projectId,
      pull.id,
      associationSelection.value,
      associationReason.value,
    );
    associationReason.value = "";
    // `isCurrent` and the six preconditions are derived from the pull request, so
    // the Review has to be re-read rather than patched locally.
    detail.value = await getReview(ids.projectId, ids.reviewId);
    history.value = await listPullRequestReviews(ids.projectId, pull.id);
  } catch (failure: unknown) {
    associationError.value = apiErrorMessage(failure);
  } finally {
    associationPending.value = false;
  }
}

async function decide(decision: "APPROVE" | "REQUEST_CHANGES"): Promise<void> {
  const ids = target();
  if (ids === null) {
    return;
  }
  decisionPending.value = true;
  decisionError.value = null;
  try {
    await decideReview(ids.projectId, ids.reviewId, decision, decisionComment.value);
    decisionComment.value = "";
    detail.value = await getReview(ids.projectId, ids.reviewId);
    if (pullRequest.value !== null) {
      history.value = await listPullRequestReviews(ids.projectId, pullRequest.value.id);
    }
  } catch (failure: unknown) {
    decisionError.value = apiErrorMessage(failure);
  } finally {
    decisionPending.value = false;
  }
}

async function move(
  findingId: number,
  status: FindingStatus,
  action: FindingAction,
): Promise<void> {
  const ids = target();
  if (ids === null) {
    return;
  }
  findingPending.value = findingId;
  findingError.value = null;
  try {
    await moveFinding(ids.projectId, findingId, status, "");
    detail.value = await getReview(ids.projectId, ids.reviewId);
    if (String(findingId) in eventsByFinding.value) {
      eventsByFinding.value = {
        ...eventsByFinding.value,
        [String(findingId)]: await listFindingEvents(ids.projectId, findingId),
      };
    }
  } catch (failure: unknown) {
    findingError.value = `${action} 失败：${apiErrorMessage(failure)}`;
  } finally {
    findingPending.value = null;
  }
}

async function showEvents(findingId: number): Promise<void> {
  const ids = target();
  if (ids === null) {
    return;
  }
  eventsPendingFor.value = findingId;
  eventsError.value = null;
  try {
    eventsByFinding.value = {
      ...eventsByFinding.value,
      [String(findingId)]: await listFindingEvents(ids.projectId, findingId),
    };
  } catch (failure: unknown) {
    eventsError.value = { findingId, message: apiErrorMessage(failure) };
  } finally {
    eventsPendingFor.value = null;
  }
}

function eventsErrorFor(findingId: number): string | null {
  const failure = eventsError.value;
  return failure !== null && failure.findingId === findingId ? failure.message : null;
}
</script>

<template>
  <section class="review-detail-page" aria-labelledby="review-detail-title">
    <div class="page-head">
      <p class="eyebrow">{{ project ? `${project.name} · Review workspace` : "Review" }}</p>
      <h1 id="review-detail-title">
        {{ detail ? `审查 ${detail.id}` : "审查详情" }}
      </h1>
      <div v-if="projectId !== null" class="record-actions">
        <RouterLink class="button" :to="reviewsRoute(projectId)">返回审查列表</RouterLink>
        <RouterLink
          v-if="detail"
          class="button"
          :to="reviewsRoute(projectId, detail.pullRequestId)"
        >
          该 PR 的全部审查
        </RouterLink>
      </div>
      <p class="lede">先确认审查身份与当前有效性，再核验 AC、覆盖清单和 Finding，最后做出人工决定。</p>
    </div>

    <p v-if="!hasContext" class="alert" role="alert">
      审查详情需要项目上下文，请从审查列表进入。
    </p>
    <p v-else-if="loading" class="muted">正在加载审查…</p>
    <p v-else-if="loadError" class="alert" role="alert">{{ loadError }}</p>

    <template v-if="detail !== null">
      <div class="review-context-grid">
      <section class="panel context-panel" aria-labelledby="review-pull-request-title">
        <h2 id="review-pull-request-title" class="panel-title">所属 PR 与需求关联</h2>

        <dl class="meta-list">
          <div>
            <dt>PR 编号</dt>
            <dd class="pull-request-number">
              {{ pullRequest ? `PR #${pullRequest.externalNumber}` : "未取到 PR" }}
            </dd>
          </div>
          <div>
            <dt>PR 当前 head</dt>
            <dd>
              <code v-if="pullRequest" :title="pullRequest.headSha">
                {{ shortSha(pullRequest.headSha) }}
              </code>
              <span v-else>未取到</span>
            </dd>
          </div>
          <div>
            <dt>PR 当前关联需求</dt>
            <dd class="pull-request-requirement">
              <RouterLink
                v-if="pullRequest && pullRequest.requirementId !== null && projectId !== null"
                :to="requirementDetailRoute(projectId, pullRequest.requirementId)"
              >
                {{ requirementTitle(pullRequest.requirementId) }}
              </RouterLink>
              <span v-else>未关联需求</span>
            </dd>
          </div>
        </dl>

        <form v-if="isLeader && pullRequest" class="inline-form" @submit.prevent="saveAssociation">
          <div class="field">
            <label for="association-requirement">改为关联需求</label>
            <select id="association-requirement" v-model="associationSelection">
              <option :value="null">不关联需求（清除）</option>
              <option v-for="item in requirements" :key="item.id" :value="item.id">
                {{ item.title }}
              </option>
            </select>
          </div>
          <div class="field">
            <label for="association-reason">纠正原因</label>
            <input id="association-reason" v-model="associationReason" maxlength="500" />
          </div>
          <button type="submit" class="button button-primary" :disabled="associationPending">
            保存关联
          </button>
          <p class="field-hint">
            只有项目负责人可以改关联；清除关联也是合法纠正，两者都会写入审计。
          </p>
        </form>
        <p v-else-if="!isLeader" class="field-hint">只有项目负责人可以修改 PR 的需求关联。</p>
        <p v-if="associationError" class="alert" role="alert">{{ associationError }}</p>
      </section>

      <section class="panel identity-panel" aria-labelledby="review-identity-title">
        <h2 id="review-identity-title" class="panel-title">Review 元信息</h2>

        <dl class="meta-list">
          <div>
            <dt>执行状态</dt>
            <dd class="review-status">
              <span :class="['badge', `badge-${REVIEW_STATUS_TONES[detail.status]}`]">
                {{ REVIEW_STATUS_LABELS[detail.status] }}
              </span>
            </dd>
          </div>
          <div>
            <dt>Decision</dt>
            <dd class="review-decision">
              <span :class="['badge', `badge-${REVIEW_DECISION_TONES[detail.decision]}`]">
                {{ REVIEW_DECISION_LABELS[detail.decision] }}
              </span>
            </dd>
          </div>
          <div>
            <dt>是否当前有效</dt>
            <dd class="review-current">
              <span :class="['badge', detail.isCurrent ? 'badge-success' : 'badge-warning']">
                {{ detail.isCurrent ? "当前有效" : "已过期" }}
              </span>
            </dd>
          </div>
          <div>
            <dt>审查的 head</dt>
            <dd><code :title="detail.headSha">{{ shortSha(detail.headSha) }}</code></dd>
          </div>
          <div>
            <dt>输入指纹</dt>
            <dd>
              <code :title="detail.reviewInputFingerprint">
                {{ shortSha(detail.reviewInputFingerprint) }}
              </code>
            </dd>
          </div>
          <div>
            <dt>审查的需求版本</dt>
            <dd>
              {{
                detail.requirementRevisionId === null
                  ? "未关联需求版本"
                  : `需求版本 ${detail.requirementRevisionId}`
              }}
            </dd>
          </div>
          <div>
            <dt>执行次数</dt>
            <dd>第 {{ detail.executionAttempt }} 次</dd>
          </div>
          <div>
            <dt>引擎 / 提示词 / 模型</dt>
            <dd>
              {{ detail.engine ?? "未记录" }} · {{ detail.promptVersion ?? "未记录" }} ·
              {{ detail.model ?? "未记录" }}
            </dd>
          </div>
          <div>
            <dt>决定人与时间</dt>
            <dd>
              {{ memberName(detail.decisionBy) ?? "尚无" }} ·
              {{ detail.decisionAt === null ? "尚无" : formatDateTime(detail.decisionAt) }}
            </dd>
          </div>
        </dl>

        <p v-if="detail.decisionComment" class="muted">
          决定备注：{{ detail.decisionComment }}
        </p>

        <p v-if="gateBlocked" class="decision-gate" role="status">
          此 head 已有退回：只有新的 head SHA 能解除，改 Base、需求关联、需求版本或重新同步 Diff 都不解除。
        </p>
        <p v-if="!detail.isCurrent" class="review-stale" role="status">
          审查已过期：这条 Review 的 head、输入指纹或需求版本与 PR 当前值不一致。
        </p>
      </section>
      </div>

      <div class="review-evidence-grid">
      <section class="panel ac-panel" aria-labelledby="ac-verdicts-title">
        <h2 id="ac-verdicts-title" class="panel-title">验收标准覆盖判定</h2>

        <p v-if="detail.acVerdicts === null" class="empty-state ac-verdicts-missing">
          本次 Review 没有产出 AC 判定（字段缺失，与「判定为空」不是同一件事）。
        </p>
        <p v-else-if="detail.acVerdicts.length === 0" class="empty-state">
          AC 判定是一个空列表：这条 Review 记录了判定结果，但其中没有任何一条 AC。
        </p>
        <ul v-else class="verdict-list">
          <li v-for="verdict in detail.acVerdicts" :key="verdict.acId" class="ac-verdict">
            <span class="badge badge-neutral">{{ verdict.acKey }}</span>
            <span :class="['badge', `badge-${AC_VERDICT_TONES[verdict.verdict]}`]">
              {{ AC_VERDICT_LABELS[verdict.verdict] }}
            </span>
          </li>
        </ul>
      </section>

      <section class="panel coverage-panel" aria-labelledby="coverage-title">
        <h2 id="coverage-title" class="panel-title">审查覆盖与未审查文件</h2>

        <p v-if="detail.coverage === null" class="empty-state coverage-missing">
          本次 Review 没有记录覆盖清单（字段缺失）。缺失不等于「全部审查过」。
        </p>
        <template v-else>
          <dl class="meta-list">
            <div>
              <dt>是否发生截断</dt>
              <dd class="coverage-truncated">
                <span :class="['badge', detail.coverage.truncated ? 'badge-warning' : 'badge-success']">
                  {{ detail.coverage.truncated ? "有内容被截断" : "没有截断" }}
                </span>
              </dd>
            </div>
            <div>
              <dt>已审查文件</dt>
              <dd>{{ detail.coverage.files.length }} 个</dd>
            </div>
            <div>
              <dt>未审查文件</dt>
              <dd class="coverage-not-reviewed-count">
                {{ detail.coverage.notReviewed.length }} 个
              </dd>
            </div>
          </dl>

          <h3 class="subsection-title">未审查文件清单</h3>
          <p v-if="detail.coverage.notReviewed.length === 0" class="muted coverage-not-reviewed-empty">
            未审查文件：0 个。这一轮把改动文件全部送进了审查。
          </p>
          <ul v-else class="path-list coverage-not-reviewed">
            <li v-for="path in detail.coverage.notReviewed" :key="path">
              <code>{{ path }}</code>
            </li>
          </ul>

          <h3 class="subsection-title">已审查文件</h3>
          <p v-if="detail.coverage.files.length === 0" class="muted">已审查文件：0 个。</p>
          <ul v-else class="path-list">
            <li v-for="file in detail.coverage.files" :key="file.path">
              <code>{{ file.path }}</code>
              <span v-if="file.patchTruncated" class="badge badge-warning">补丁被截断</span>
            </li>
          </ul>
        </template>
      </section>
      </div>

      <section class="panel findings-panel" aria-labelledby="findings-title">
        <h2 id="findings-title" class="panel-title">Finding（{{ detail.findings.length }} 条）</h2>
        <p class="field-hint">一次性全部渲染，不分页也不虚拟化。</p>

        <p v-if="findingError" class="alert" role="alert">{{ findingError }}</p>
        <p v-if="detail.findings.length === 0" class="empty-state">这条 Review 没有 Finding。</p>

        <ul v-else class="record-list">
          <FindingCard
            v-for="finding in detail.findings"
            :key="finding.id"
            :finding="finding"
            :review-decision="detail.decision"
            :assignee-name="memberName(finding.assigneeId)"
            :role="role"
            :pending="findingPending === finding.id"
            :events="eventsByFinding[String(finding.id)] ?? null"
            :events-pending="eventsPendingFor === finding.id"
            :events-error="eventsErrorFor(finding.id)"
            @move="(status, action) => move(finding.id, status, action)"
            @show-events="showEvents(finding.id)"
          />
        </ul>
      </section>

      <section class="panel decision-panel" aria-labelledby="review-decision-title">
        <div class="decision-panel-head">
          <div>
            <p class="eyebrow">Human decision gate</p>
            <h2 id="review-decision-title" class="panel-title">终局决定</h2>
          </div>
          <span :class="['badge', `badge-${REVIEW_DECISION_TONES[detail.decision]}`]">
            {{ REVIEW_DECISION_LABELS[detail.decision] }}
          </span>
        </div>

        <p v-if="!canDecide" class="field-hint">
          只有项目负责人与评审可以做终局决定；开发可以触发重审并修复 Finding。
        </p>

        <template v-else>
          <div class="field">
            <label for="decision-comment">决定备注</label>
            <textarea id="decision-comment" v-model="decisionComment" rows="3"></textarea>
          </div>
          <div class="form-actions decision-actions">
            <button
              type="button"
              class="button button-success"
              data-decision="APPROVE"
              :disabled="decisionPending || !canSubmitDecision"
              @click="decide('APPROVE')"
            >
              通过（APPROVE）
            </button>
            <button
              type="button"
              class="button button-danger"
              data-decision="REQUEST_CHANGES"
              :disabled="decisionPending || !canSubmitDecision"
              @click="decide('REQUEST_CHANGES')"
            >
              退回（REQUEST_CHANGES）
            </button>
          </div>

          <ul v-if="decisionBlockers.length > 0" class="decision-blockers">
            <li v-for="blocker in decisionBlockers" :key="blocker">{{ blocker }}</li>
          </ul>
          <p class="field-hint">终局决定只写一次，不可撤销、不可覆盖。</p>
        </template>

        <p v-if="decisionError" class="alert" role="alert">{{ decisionError }}</p>
      </section>

      <section class="panel snapshot-panel" aria-labelledby="context-snapshot-title">
        <h2 id="context-snapshot-title" class="panel-title">不可变上下文快照</h2>
        <p class="field-hint">
          历史页面不反查 PR 当前关联，这里原样呈现审查当时保存的快照。
        </p>
        <p v-if="contextSnapshotText === null" class="empty-state context-snapshot-missing">
          这条 Review 没有记录上下文快照。
        </p>
        <pre v-else class="context-snapshot">{{ contextSnapshotText }}</pre>
      </section>
    </template>
  </section>
</template>

<style scoped>
.review-context-grid,
.review-evidence-grid {
  display: grid;
  align-items: start;
  gap: var(--fp-space-6);
}

.review-context-grid {
  grid-template-columns: minmax(20rem, 0.85fr) minmax(0, 1.15fr);
}

.review-evidence-grid {
  grid-template-columns: minmax(18rem, 0.72fr) minmax(0, 1.28fr);
}

.context-panel,
.identity-panel,
.ac-panel,
.coverage-panel {
  height: 100%;
}

.identity-panel,
.findings-panel,
.decision-panel {
  border-color: var(--fp-color-border-accent);
}

.decision-panel {
  background: var(--fp-gradient-panel);
  box-shadow: var(--fp-shadow-elevated), var(--fp-shadow-accent);
}

.decision-panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--fp-space-4);
  margin-bottom: var(--fp-space-5);
}

.decision-panel-head .eyebrow {
  margin-bottom: var(--fp-space-2);
}

.decision-panel-head .panel-title {
  margin-bottom: 0;
}

.decision-gate,
.review-stale {
  margin: var(--fp-space-3) 0 0;
  padding: var(--fp-space-3) var(--fp-space-4);
  border: 0.0625rem solid var(--fp-color-warning);
  border-left-width: var(--fp-space-1);
  border-radius: var(--fp-radius-sm);
  color: var(--fp-color-warning);
  line-height: 1.5;
}

.decision-actions {
  margin-top: var(--fp-space-4);
}

.decision-blockers {
  display: grid;
  gap: var(--fp-space-2);
  margin: var(--fp-space-3) 0 0;
  padding-left: var(--fp-space-6);
  color: var(--fp-color-text-muted);
  font-size: 0.8125rem;
}

.verdict-list {
  display: grid;
  gap: var(--fp-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.ac-verdict {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--fp-space-2);
}

.subsection-title {
  margin: var(--fp-space-6) 0 var(--fp-space-2);
  font-size: 0.9375rem;
}

.path-list {
  display: grid;
  gap: var(--fp-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
  font-family: var(--fp-font-mono);
  font-size: 0.8125rem;
  word-break: break-all;
}

.context-snapshot {
  max-height: 22rem;
  margin: 0;
  padding: var(--fp-space-3);
  overflow: auto;
  border: 0.0625rem solid var(--fp-color-border);
  border-radius: var(--fp-radius-sm);
  background: var(--fp-color-surface-muted);
  font-family: var(--fp-font-mono);
  font-size: 0.8125rem;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.snapshot-panel {
  border-style: dashed;
}

@media (max-width: 64rem) {
  .review-context-grid,
  .review-evidence-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 42rem) {
  .decision-panel-head {
    flex-direction: column;
  }
}
</style>
