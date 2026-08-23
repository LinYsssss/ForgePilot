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
  type Finding,
  type FindingAction,
  type FindingEvent,
  type FindingStatus,
  type ReviewDetail,
  type ReviewSummary,
} from "./api";
import DiffEvidenceViewer from "./DiffEvidenceViewer.vue";
import FindingCard from "./FindingCard.vue";
import { parseReviewContext } from "./context";
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
const selectedFindingId = ref<number | null>(null);
const selectedPath = ref<string | null>(null);
const findingComments = ref<Record<string, string>>({});
let detailLoadToken = 0;

const role = computed(() => project.value?.myRole ?? null);
const isLeader = computed(() => role.value === "LEADER");
const canDecide = computed(() => role.value === "LEADER" || role.value === "REVIEWER");

const canEditAssociation = computed(
  () => pullRequest.value?.canEditRequirementAssociation ?? false,
);

function target(): { projectId: number; reviewId: number } | null {
  const pid = projectId.value;
  const rid = reviewId.value;
  return pid === null || rid === null ? null : { projectId: pid, reviewId: rid };
}

const hasContext = computed(() => target() !== null);

/**
 * ARCHITECTURE.md 3.1 的决策闸门：只要 PR 的*当前* head 上存在任何一次
 * `REQUEST_CHANGES`，这个 head 就被永久封锁。它在每次读取时从数据行中现算、
 * 而不是缓存下来，因为 force-push 回退到一个被封锁的 head 时必须重新上锁，
 * 而一个存下来的标志位做不到。
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
 * 那六项前置条件，在这里求值**仅仅是为了解释按钮为何被禁用**。
 * 服务端会在行锁之下重新检查全部六项，并且始终是唯一的权威；
 * 这份列表从不授予任何权限。
 *
 * <p>前置条件 3、4、5 是从 `isCurrent` 读出来的，而不是在这里重算：
 * 那个标志是服务端自己对「head、指纹与需求修订三者都等于 PR 当前值」的推导结果，
 * 而 PR 的响应里压根没有携带它当前的需求修订 id。head 与指纹在这里是可见的，
 * 因此当其中之一能解释这次不匹配时就点名它；当两者都解释不了时，
 * 剩下的原因就是需求修订。
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

const reviewContext = computed(() => parseReviewContext(detail.value?.contextSnapshot));
const activeFindings = computed(() =>
  detail.value?.findings.filter((finding) => finding.continuity !== "SUPPRESSED") ?? [],
);
const suppressedFindings = computed(() =>
  detail.value?.findings.filter((finding) => finding.continuity === "SUPPRESSED") ?? [],
);
const selectedFinding = computed<Finding | null>(() =>
  detail.value?.findings.find((finding) => finding.id === selectedFindingId.value) ?? null,
);
const memberNames = computed<Record<string, string>>(() =>
  Object.fromEntries(members.value.map((member) => [String(member.userId), member.username])),
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
  const token = ++detailLoadToken;
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
  findingComments.value = {};
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
    if (token !== detailLoadToken) {
      return;
    }
    project.value = loadedProject;
    members.value = loadedMembers;
    requirements.value = loadedRequirements;
    detail.value = loadedReview;
    selectedPath.value = parseReviewContext(loadedReview.contextSnapshot)?.changedFiles[0]?.path ?? null;

    const [loadedPullRequest, loadedHistory] = await Promise.all([
      getPullRequest(ids.projectId, loadedReview.pullRequestId),
      listPullRequestReviews(ids.projectId, loadedReview.pullRequestId),
    ]);
    if (token !== detailLoadToken) {
      return;
    }
    pullRequest.value = loadedPullRequest;
    history.value = loadedHistory;
    associationSelection.value = loadedPullRequest.requirementId;
  } catch (failure: unknown) {
    if (token === detailLoadToken) {
      loadError.value = apiErrorMessage(failure);
    }
  } finally {
    if (token === detailLoadToken) {
      loading.value = false;
    }
  }
}

// 用 watch 而不是 mounted：仅 `:id` 变化时 vue-router 会复用本组件，
// 因此放在 `onMounted` 里加载会一直显示上一次 Review。
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
    // `isCurrent` 与那六项前置条件都是从 PR 推导出来的，
    // 因此必须重新读取该 Review，而不能在本地打补丁。
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
  comment: string,
): Promise<void> {
  const ids = target();
  if (ids === null) {
    return;
  }
  findingPending.value = findingId;
  findingError.value = null;
  try {
    await moveFinding(ids.projectId, findingId, status, comment);
    setFindingComment(findingId, "");
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

function setFindingComment(findingId: number, comment: string): void {
  findingComments.value = { ...findingComments.value, [String(findingId)]: comment };
}

function selectFinding(finding: Finding): void {
  selectedFindingId.value = finding.id;
  if (finding.path !== null && reviewContext.value?.changedFiles.some((file) => file.path === finding.path)) {
    selectedPath.value = finding.path;
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
      <p class="lede">唯一 AI Review Engine 将 Requirement、AC、向量召回的项目知识与 Diff 形成证据；Finding 生命周期和最终 Decision 始终由人完成。</p>
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

        <form
          v-if="canEditAssociation && pullRequest"
          class="inline-form"
          @submit.prevent="saveAssociation"
        >
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
            清除关联也是合法纠正，两者都会写入审计。
          </p>
          <p v-if="!isLeader" class="field-hint">
            你是这个 PR 的作者，因此在本 head 出现终局裁定之前可以纠正关联。
          </p>
        </form>
        <p v-else-if="!canEditAssociation" class="field-hint">
          只有项目负责人，或纠正窗口仍开放的 PR 作者可以修改需求关联。
        </p>
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

      <section class="panel evidence-workspace" aria-labelledby="evidence-workspace-title">
        <div class="workspace-head">
          <div>
            <p class="eyebrow">Immutable review evidence</p>
            <h2 id="evidence-workspace-title" class="panel-title">审查证据工作台</h2>
          </div>
          <span v-if="selectedFinding" class="badge badge-warning">
            正在定位发现 {{ selectedFinding.id }}
          </span>
        </div>

        <p v-if="reviewContext === null" class="alert context-snapshot-invalid" role="alert">
          上下文快照缺失或结构不完整，无法安全构建证据联动；原始载荷仍保留在页面底部供诊断。
        </p>

        <template v-else>
          <div class="snapshot-summary-grid">
            <section class="snapshot-card" aria-labelledby="snapshot-requirement-title">
              <h3 id="snapshot-requirement-title" class="subsection-title">审查时的需求与 AC</h3>
              <p v-if="reviewContext.requirement === null" class="empty-state">
                这轮 Review 没有关联需求。
              </p>
              <template v-else>
                <p class="snapshot-title">{{ reviewContext.requirement.title }}</p>
                <p class="muted">{{ reviewContext.requirement.background ?? "未记录背景" }}</p>
                <p class="muted">{{ reviewContext.requirement.description ?? "未记录描述" }}</p>
              </template>
              <ol class="snapshot-criteria">
                <li
                  v-for="criterion in reviewContext.acceptanceCriteria"
                  :key="criterion.id"
                  :class="{ 'snapshot-criterion-selected': selectedFinding?.acKey === criterion.acKey }"
                >
                  <span class="badge badge-neutral">{{ criterion.acKey }}</span>
                  {{ criterion.text }}
                </li>
              </ol>
            </section>

            <section class="snapshot-card" aria-labelledby="snapshot-knowledge-title">
              <h3 id="snapshot-knowledge-title" class="subsection-title">本 Review 的向量语义召回知识证据集</h3>
              <p class="field-hint">
                响应没有 Finding 到知识块的一对一关联，因此这里只呈现本轮召回集合，不伪造对应关系。
              </p>
              <p v-if="reviewContext.knowledgeEvidence.length === 0" class="empty-state">
                本轮没有召回知识证据。
              </p>
              <ol v-else class="knowledge-list">
                <li v-for="evidence in reviewContext.knowledgeEvidence" :key="evidence.chunkId">
                  <div class="record-head">
                    <span class="badge badge-neutral">块 {{ evidence.chunkId }}</span>
                    <span class="badge badge-info">向量语义召回相似度 {{ evidence.score.toFixed(3) }}</span>
                  </div>
                  <p>{{ evidence.excerpt }}</p>
                  <small>来源 {{ evidence.sourceId }} · 文档 {{ evidence.documentId }}</small>
                </li>
              </ol>
            </section>
          </div>

          <div class="snapshot-pr-bar">
            <span>{{ reviewContext.pullRequest.provider }} · {{ reviewContext.pullRequest.repository }} #{{ reviewContext.pullRequest.number }}</span>
            <strong>{{ reviewContext.pullRequest.title }}</strong>
            <code :title="reviewContext.pullRequest.headSha">{{ shortSha(reviewContext.pullRequest.headSha) }}</code>
          </div>

          <DiffEvidenceViewer
            :files="reviewContext.changedFiles"
            :selected-path="selectedPath"
            :finding="selectedFinding"
            @select-path="selectedPath = $event"
          />
        </template>
      </section>

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

        <ul v-if="activeFindings.length > 0" class="record-list">
          <FindingCard
            v-for="finding in activeFindings"
            :key="finding.id"
            :finding="finding"
            :review-decision="detail.decision"
            :assignee-name="memberName(finding.assigneeId)"
            :role="role"
            :pending="findingPending === finding.id"
            :events="eventsByFinding[String(finding.id)] ?? null"
            :events-pending="eventsPendingFor === finding.id"
            :events-error="eventsErrorFor(finding.id)"
            :selected="selectedFindingId === finding.id"
            :actor-names="memberNames"
            :comment="findingComments[String(finding.id)] ?? ''"
            @move="(status, action, comment) => move(finding.id, status, action, comment)"
            @show-events="showEvents(finding.id)"
            @select="selectFinding(finding)"
            @update-comment="setFindingComment(finding.id, $event)"
          />
        </ul>

        <details v-if="suppressedFindings.length > 0" class="suppressed-findings">
          <summary>继承抑制的 Finding（{{ suppressedFindings.length }} 条）</summary>
          <p class="field-hint">这些 Finding 来自跨轮继承抑制，与本轮活跃 Finding 分组展示。</p>
          <ul class="record-list">
            <FindingCard
              v-for="finding in suppressedFindings"
              :key="finding.id"
              :finding="finding"
              :review-decision="detail.decision"
              :assignee-name="memberName(finding.assigneeId)"
              :role="role"
              :pending="findingPending === finding.id"
              :events="eventsByFinding[String(finding.id)] ?? null"
              :events-pending="eventsPendingFor === finding.id"
              :events-error="eventsErrorFor(finding.id)"
              :selected="selectedFindingId === finding.id"
              :actor-names="memberNames"
              :comment="findingComments[String(finding.id)] ?? ''"
              @move="(status, action, comment) => move(finding.id, status, action, comment)"
              @show-events="showEvents(finding.id)"
              @select="selectFinding(finding)"
              @update-comment="setFindingComment(finding.id, $event)"
            />
          </ul>
        </details>
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

      <details class="panel snapshot-panel">
        <summary id="context-snapshot-title">诊断：查看不可变上下文原始 JSON</summary>
        <p class="field-hint">历史页面不反查 PR 当前关联；这里原样呈现审查当时保存的载荷。</p>
        <p v-if="contextSnapshotText === null" class="empty-state context-snapshot-missing">这条 Review 没有记录上下文快照。</p>
        <pre v-else class="context-snapshot">{{ contextSnapshotText }}</pre>
      </details>
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
.evidence-workspace,
.findings-panel,
.decision-panel {
  border-color: var(--fp-color-border-accent);
}

.workspace-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--fp-space-4);
  margin-bottom: var(--fp-space-5);
}

.workspace-head .eyebrow {
  margin-bottom: var(--fp-space-2);
}

.workspace-head .panel-title {
  margin-bottom: 0;
}

.snapshot-summary-grid {
  display: grid;
  gap: var(--fp-space-4);
  margin-bottom: var(--fp-space-4);
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.snapshot-card {
  min-width: 0;
  padding: var(--fp-space-4);
  border: 0.0625rem solid var(--fp-color-border);
  border-radius: var(--fp-radius-md);
  background: var(--fp-color-canvas-muted);
}

.snapshot-card .subsection-title {
  margin-top: 0;
}

.snapshot-title {
  margin: 0 0 var(--fp-space-2);
  color: var(--fp-color-text);
  font-size: 1rem;
  font-weight: 750;
}

.snapshot-criteria,
.knowledge-list {
  display: grid;
  gap: var(--fp-space-2);
  margin: var(--fp-space-4) 0 0;
  padding: 0;
  list-style: none;
}

.snapshot-criteria li,
.knowledge-list li {
  padding: var(--fp-space-3);
  border: 0.0625rem solid var(--fp-color-border);
  border-radius: var(--fp-radius-sm);
  line-height: 1.6;
}

.snapshot-criterion-selected {
  border-color: var(--fp-color-warning) !important;
  background: var(--fp-color-warning-soft);
  box-shadow: inset 0.1875rem 0 var(--fp-color-warning);
}

.knowledge-list {
  max-height: 22rem;
  overflow: auto;
}

.knowledge-list p {
  margin: var(--fp-space-2) 0;
  color: var(--fp-color-text-muted);
}

.knowledge-list small {
  color: var(--fp-color-text-subtle);
  font-family: var(--fp-font-mono);
}

.snapshot-pr-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--fp-space-3);
  margin-bottom: var(--fp-space-3);
  padding: var(--fp-space-3) var(--fp-space-4);
  border: 0.0625rem solid var(--fp-color-border);
  border-radius: var(--fp-radius-sm);
  background: var(--fp-color-surface-strong);
}

.snapshot-pr-bar strong {
  margin-right: auto;
}

.suppressed-findings {
  margin-top: var(--fp-space-5);
  padding: var(--fp-space-4);
  border: 0.0625rem dashed var(--fp-color-border-strong);
  border-radius: var(--fp-radius-md);
}

.suppressed-findings > summary,
.snapshot-panel > summary {
  color: var(--fp-color-text-muted);
  cursor: pointer;
  font-weight: 750;
}

.suppressed-findings > .field-hint {
  margin: var(--fp-space-3) 0;
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
  .review-evidence-grid,
  .snapshot-summary-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 42rem) {
  .decision-panel-head {
    flex-direction: column;
  }

  .workspace-head {
    flex-direction: column;
  }
}
</style>
