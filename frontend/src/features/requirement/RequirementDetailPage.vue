<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";

import { parseId, requirementsRoute, reviewDetailRoute, PROJECT_QUERY_KEY } from "../../app/routes";
import { formatDateTime } from "../../lib/datetime";
import { apiErrorMessage } from "../../lib/http";
import { useFinitePolling } from "../../composables/useFinitePolling";
import { useSession } from "../auth/session";
import { getProject, hasProjectRole, listMembers, type Member, type Project } from "../project/api";
import {
  getRequirementCoverage,
  getRequirementReviewActivity,
  getReview,
  listProjectReviews,
  type ActivityView,
  type RequirementCoverage,
  type ReviewDetail,
} from "../review/api";
import {
  AC_VERDICT_LABELS,
  AC_VERDICT_TONES,
  PULL_REQUEST_ACTIVITIES,
  PULL_REQUEST_ACTIVITY_LABELS,
  REVIEW_ACTIVITY_LABELS,
  REVIEW_ACTIVITY_TONES,
} from "../review/labels";
import AcceptanceCriteriaEditor from "./AcceptanceCriteriaEditor.vue";
import {
  attachmentDownloadUrl,
  assign,
  assignReviewer,
  checkQuality,
  changeStatus,
  deleteRequirement,
  editDraft,
  generateGuidance,
  getAttachmentContent,
  getRequirement,
  listAttachments,
  listRevisions,
  publishRevision,
  promoteAttachment,
  uploadAttachment,
  toDraft,
  type AcceptanceCriterionDraft,
  type ImplementationGuidance,
  type QualityReport,
  type RequirementDocumentContent,
  type RequirementDetail,
  type Revision,
  type RevisionContent,
} from "./api";
import type { KnowledgeDocument, KnowledgeStatus } from "../knowledge/api";
import {
  isTerminal,
  requirementPhase,
  requirementHandler,
  REQUIREMENT_STATUS_LABELS,
  REQUIREMENT_STATUS_TONES,
  STATUS_TRANSITIONS,
  type RequirementStatus,
} from "./status";

const route = useRoute();
const router = useRouter();
const { account } = useSession();

const projectId = computed(() => parseId(route.query[PROJECT_QUERY_KEY]));
const requirementId = computed(() => parseId(route.params.id));

const project = ref<Project | null>(null);
const members = ref<Member[]>([]);
const detail = ref<RequirementDetail | null>(null);
const revisions = ref<Revision[]>([]);
const reviewActivity = ref<ActivityView | null>(null);
const coverage = ref<RequirementCoverage | null>(null);
const returnedReviews = ref<ReviewDetail[]>([]);
const returnsError = ref<string | null>(null);
const loading = ref(true);
const loadError = ref<string | null>(null);

const draftTitle = ref("");
const draftBackground = ref("");
const draftDescription = ref("");
const draftCriteria = ref<AcceptanceCriterionDraft[]>([]);
const changeReason = ref("");
const assigneeSelection = ref<number | null>(null);
const reviewerSelection = ref<number | null>(null);
const reviewerCandidates = computed(() => members.value.filter(member => member.roles.includes("REVIEWER") || member.roles.includes("LEADER")));
const developerCandidates = computed(() => members.value.filter(member => member.roles.includes("DEVELOPER") || member.roles.includes("LEADER")));

const actionError = ref<string | null>(null);
const actionPending = ref(false);

const qualityReport = ref<QualityReport | null>(null);
const qualityPending = ref(false);
const qualityError = ref<string | null>(null);
const guidance = ref<ImplementationGuidance | null>(null);
const guidancePending = ref(false);
const guidanceError = ref<string | null>(null);
const guidanceCopyFeedback = ref<string | null>(null);
let guidanceRequest = 0;
const attachments = ref<KnowledgeDocument[]>([]);
const attachmentFile = ref<File | null>(null);
const attachmentPending = ref(false);
const attachmentError = ref<string | null>(null);
const selectedDocument = ref<RequirementDocumentContent | null>(null);
const documentPending = ref(false);
const documentError = ref<string | null>(null);
let detailLoadToken = 0;

const isLeader = computed(() => hasProjectRole(project.value, "LEADER"));
const isDraft = computed(() => detail.value?.status === "DRAFT");
const editable = computed(
  () => isLeader.value && detail.value !== null && !isTerminal(detail.value.status),
);
const canCheckQuality = computed(() => isLeader.value);
const canGenerateGuidance = computed(
  () =>
    isLeader.value ||
    (hasProjectRole(project.value, "DEVELOPER") &&
      detail.value?.assigneeId !== null &&
      detail.value?.assigneeId === account.value?.id),
);
const hasUnsavedContent = computed(() => {
  if (detail.value === null) return false;
  const saved = toDraft(detail.value.currentRevision);
  // 可选正文的空串与 null 等价；保存和脏状态使用同一份表单 payload。
  return JSON.stringify(draftContent()) !== JSON.stringify({
    ...saved,
    background: saved.background || null,
    description: saved.description || null,
  });
});
const canRunGuidance = computed(() => canGenerateGuidance.value && detail.value !== null
  && !loading.value && !actionPending.value && !guidancePending.value && !hasUnsavedContent.value);

function invalidateGuidance(): void {
  // DRAFT 原地保存仍是同一个 revisionId，必须同时撤销在途回答，不能只比版本号。
  guidanceRequest++;
  guidance.value = null;
  guidancePending.value = false;
  guidanceError.value = null;
  guidanceCopyFeedback.value = null;
}
onBeforeUnmount(invalidateGuidance);

function target(): { projectId: number; requirementId: number } | null {
  const pid = projectId.value;
  const rid = requirementId.value;
  return pid === null || rid === null ? null : { projectId: pid, requirementId: rid };
}

const attachmentPollingKey = computed(() => {
  const ids = target();
  return ids !== null && attachments.value.some(document => document.status === "PENDING")
    ? `requirement-attachments:${ids.projectId}:${ids.requirementId}`
    : null;
});
const attachmentPolling = useFinitePolling(attachmentPollingKey, async () => {
  const ids = target();
  const token = detailLoadToken;
  if (ids === null) return;
  const loaded = await listAttachments(ids.projectId, ids.requirementId);
  const current = target();
  if (token === detailLoadToken && current?.projectId === ids.projectId
    && current.requirementId === ids.requirementId) {
    attachments.value = loaded;
  }
});
const attachmentPollingError = computed(() => attachmentPolling.error.value === null
  ? null
  : apiErrorMessage(attachmentPolling.error.value));

function knowledgeStatusLabel(status: KnowledgeStatus): string {
  if (status === "READY") return "可用于召回";
  if (status === "FAILED") return "处理失败";
  return "正在处理";
}

const hasContext = computed(() => target() !== null);

function applyDetail(loaded: RequirementDetail): void {
  const previous = detail.value?.currentRevision;
  const revisionChanged = previous?.id !== loaded.currentRevision.id;
  if (revisionChanged) qualityReport.value = null;
  // 服务器也可能返回同一草稿的新内容，不能仅凭相同 id 继续展示旧建议。
  if (revisionChanged || (previous !== undefined
    && JSON.stringify(toDraft(previous)) !== JSON.stringify(toDraft(loaded.currentRevision)))) {
    invalidateGuidance();
  }
  detail.value = loaded;
  assigneeSelection.value = loaded.assigneeId;
  reviewerSelection.value = loaded.reviewerName === null ? null : loaded.reviewerId;
  const content = toDraft(loaded.currentRevision);
  draftTitle.value = content.title;
  draftBackground.value = content.background ?? "";
  draftDescription.value = content.description ?? "";
  draftCriteria.value = content.acceptanceCriteria;
  changeReason.value = "";
}

async function load(): Promise<void> {
  const token = ++detailLoadToken;
  const ids = target();
  loading.value = true;
  loadError.value = null;
  detail.value = null;
  revisions.value = [];
  reviewActivity.value = null;
  coverage.value = null;
  returnedReviews.value = [];
  returnsError.value = null;
  qualityReport.value = null;
  invalidateGuidance();
  attachments.value = [];
  selectedDocument.value = null;
  documentError.value = null;
  if (ids === null) {
    loading.value = false;
    return;
  }
  try {
    const [
      loadedProject,
      loadedMembers,
      loadedDetail,
      loadedRevisions,
      loadedActivity,
      loadedAttachments,
      loadedCoverage,
    ] = await Promise.all([
        getProject(ids.projectId),
        listMembers(ids.projectId),
        getRequirement(ids.projectId, ids.requirementId),
        listRevisions(ids.projectId, ids.requirementId),
        getRequirementReviewActivity(ids.projectId, ids.requirementId),
        listAttachments(ids.projectId, ids.requirementId),
        // 覆盖度是补充面板，不是本页的主体数据：它失败时这一块不显示，
      // 而不是把整页拖成加载失败。放进同一个 Promise.all 只为省一次往返，
      // 因此这里必须先把它的失败吃掉，否则它就有了拖垮整页的权力。
      getRequirementCoverage(ids.projectId, ids.requirementId).catch(() => null),
      ]);
    if (token !== detailLoadToken) {
      return;
    }
    project.value = loadedProject;
    members.value = loadedMembers;
    revisions.value = loadedRevisions;
    reviewActivity.value = loadedActivity;
    attachments.value = loadedAttachments;
    coverage.value = loadedCoverage;
    applyDetail(loadedDetail);
    if (loadedActivity.counts.CHANGES_REQUESTED > 0) {
      try {
        const reviews = await listProjectReviews(ids.projectId);
        const returned = await Promise.all(reviews
          .filter(review => review.requirementId === ids.requirementId && review.isCurrent
            && review.decision === "REQUEST_CHANGES")
          .map(review => getReview(ids.projectId, review.id)));
        if (token === detailLoadToken) returnedReviews.value = returned;
      } catch (failure: unknown) {
        if (token === detailLoadToken) returnsError.value = apiErrorMessage(failure);
      }
    }
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

watch([projectId, requirementId], load, { immediate: true });

async function run(
  action: (ids: { projectId: number; requirementId: number }) => Promise<RequirementDetail>,
  clearAdvice = false,
): Promise<void> {
  const ids = target();
  if (ids === null) {
    return;
  }
  actionPending.value = true;
  actionError.value = null;
  try {
    applyDetail(await action(ids));
    if (clearAdvice) {
      qualityReport.value = null;
      invalidateGuidance();
    }
    revisions.value = await listRevisions(ids.projectId, ids.requirementId);
  } catch (failure: unknown) {
    actionError.value = apiErrorMessage(failure);
  } finally {
    actionPending.value = false;
  }
}

async function runQualityCheck(): Promise<void> {
  const ids = target();
  if (ids === null || !canCheckQuality.value) {
    return;
  }
  qualityPending.value = true;
  qualityError.value = null;
  try {
    qualityReport.value = await checkQuality(ids.projectId, ids.requirementId);
  } catch (failure: unknown) {
    qualityError.value = apiErrorMessage(failure);
  } finally {
    qualityPending.value = false;
  }
}

async function runGuidance(): Promise<void> {
  const ids = target();
  const revisionId = detail.value?.currentRevision.id;
  if (ids === null || revisionId === undefined || !canRunGuidance.value) return;
  const request = ++guidanceRequest;
  guidancePending.value = true;
  guidanceError.value = null;
  guidanceCopyFeedback.value = null;
  try {
    const answer = await generateGuidance(ids.projectId, ids.requirementId);
    if (request !== guidanceRequest) return;
    if (answer.requirementId !== ids.requirementId || answer.revisionId !== revisionId) {
      invalidateGuidance();
      guidanceError.value = "需求版本已变化，请刷新页面后重新生成建议。";
      return;
    }
    guidance.value = answer;
  } catch (failure: unknown) {
    if (request === guidanceRequest) guidanceError.value = apiErrorMessage(failure);
  } finally {
    // 保存后的旧错误与 finally 也不能清掉新请求的等待状态。
    if (request === guidanceRequest) guidancePending.value = false;
  }
}

function pickAttachment(event: Event): void {
  attachmentFile.value = (event.target as HTMLInputElement).files?.[0] ?? null;
}

async function uploadSelectedAttachment(): Promise<void> {
  const ids = target();
  const file = attachmentFile.value;
  if (ids === null || file === null) return;
  attachmentPending.value = true; attachmentError.value = null;
  try { await uploadAttachment(ids.projectId, ids.requirementId, file.name, await file.text()); attachmentFile.value = null; attachments.value = await listAttachments(ids.projectId, ids.requirementId); }
  catch (failure: unknown) { attachmentError.value = apiErrorMessage(failure); }
  finally { attachmentPending.value = false; }
}

async function promote(documentId: number): Promise<void> {
  const ids = target(); if (ids === null) return;
  attachmentPending.value = true; attachmentError.value = null;
  try { await promoteAttachment(ids.projectId, ids.requirementId, documentId); attachments.value = await listAttachments(ids.projectId, ids.requirementId); }
  catch (failure: unknown) { attachmentError.value = apiErrorMessage(failure); }
  finally { attachmentPending.value = false; }
}

async function viewAttachment(documentId: number): Promise<void> {
  const ids = target();
  if (ids === null) return;
  documentPending.value = true;
  documentError.value = null;
  selectedDocument.value = null;
  try {
    selectedDocument.value = await getAttachmentContent(
      ids.projectId,
      ids.requirementId,
      documentId,
    );
  } catch (failure: unknown) {
    documentError.value = apiErrorMessage(failure);
  } finally {
    documentPending.value = false;
  }
}

function downloadUrl(documentId: number): string {
  const ids = target();
  return ids === null ? "" : attachmentDownloadUrl(ids.projectId, ids.requirementId, documentId);
}

function exportRequirement(): void {
  const loaded = detail.value;
  if (loaded === null) return;
  const revision = loaded.currentRevision;
  const sections = [`# ${revision.title}`];
  if (revision.background) sections.push(`## 背景\n\n${revision.background}`);
  if (revision.description) sections.push(`## 描述\n\n${revision.description}`);
  sections.push(`## 验收条件\n\n${revision.acceptanceCriteria
    .map((criterion) => `- **${criterion.acKey}**：${criterion.text}`)
    .join("\n")}`);

  downloadMarkdown(`${sections.join("\n\n")}\n`, `REQ-${loaded.id}-v${revision.seq}.md`);
}

function downloadMarkdown(text: string, fileName: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: "text/markdown;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}

function guidanceMarkdown(result: ImplementationGuidance): string {
  const list = (items: string[], empty: string, numbered = false) => items.length === 0 ? empty
    : items.map((item, index) => `${numbered ? `${index + 1}.` : "-"} ${item}`).join("\n");
  return [
    `# REQ-${result.requirementId} 实现建议`,
    `需求修订：v${result.revisionSeq}（修订 ID ${result.revisionId}）\n建议版本：${result.guidanceVersion ?? "未记录"}`,
    "基于生成时已保存的需求与本次参考资料；未读取仓库源码，不会自动执行，也不保证建议正确。",
    `## 建议概览\n\n${result.summary ?? "旧版结果未提供概览。"}`,
    `## 开始前需确认\n\n${result.questions === undefined ? "旧版结果未提供待确认事项。"
      : list(result.questions, "本次未提出待确认事项。")}`,
    `## 实施清单\n\n${list(result.checklist, "本次未提供可直接执行的步骤。", true)}`,
    `## 项目规则\n\n${list(result.rules, "本次未提出额外规则。")}`,
    `## 风险提示\n\n${list(result.risks, "本次未提出明确风险，不代表已确认无风险。")}`,
    `## 本次参考资料\n\n${result.knowledgeSources.length === 0 ? "本次未召回知识资料。"
      : result.knowledgeSources.map(source => `### ${source.title}\n\n文档 ${source.documentId} · 片段 ${source.chunkSeq}\n\n${source.excerpt}`).join("\n\n")}`,
  ].join("\n\n") + "\n";
}

async function copyGuidance(): Promise<void> {
  const result = guidance.value;
  if (result === null) return;
  guidanceCopyFeedback.value = null;
  try {
    await navigator.clipboard.writeText(guidanceMarkdown(result));
    if (guidance.value === result) guidanceCopyFeedback.value = "建议已复制。";
  } catch {
    if (guidance.value === result) guidanceCopyFeedback.value = "复制失败，可下载 Markdown 保存建议。";
  }
}

function exportGuidance(): void {
  const result = guidance.value;
  if (result !== null) {
    downloadMarkdown(guidanceMarkdown(result), `REQ-${result.requirementId}-v${result.revisionSeq}-guidance.md`);
  }
}

/**
 * 删除一条**作废**需求。后端做软删：需求离开产品面，而 `ai_call_log` 与
 * `pull_request_requirement_event` 承载的调用审计与既成 PR 关联保持完整。
 * 因此这里的文案说「从列表中移除」而不是「彻底删除」——后者不是实际发生的事。
 */
async function removeRequirement(): Promise<void> {
  const ids = target();
  if (ids === null) return;
  if (!window.confirm("确认删除这条作废需求？它将从需求列表中消失，AI 调用审计与 PR 关联记录仍然保留。")) {
    return;
  }
  actionPending.value = true;
  actionError.value = null;
  try {
    await deleteRequirement(ids.projectId, ids.requirementId);
    await router.push(requirementsRoute(ids.projectId));
  } catch (failure: unknown) {
    actionError.value = apiErrorMessage(failure);
  } finally {
    actionPending.value = false;
  }
}

function draftContent(): RevisionContent {
  return {
    title: draftTitle.value,
    background: draftBackground.value === "" ? null : draftBackground.value,
    description: draftDescription.value === "" ? null : draftDescription.value,
    acceptanceCriteria: draftCriteria.value,
  };
}

function saveContent(): Promise<void> {
  const content = draftContent();
  return run(
    (ids) =>
      isDraft.value
        ? editDraft(ids.projectId, ids.requirementId, content)
        : publishRevision(ids.projectId, ids.requirementId, content, changeReason.value),
    true,
  );
}

function transitionTo(status: RequirementStatus): Promise<void> {
  // 已完成与已取消都没有出口，误点一次就再也改不回来。
  if (isTerminal(status)
    && !window.confirm(`确认把需求置为「${REQUIREMENT_STATUS_LABELS[status]}」？之后不能再改回。`)) {
    return Promise.resolve();
  }
  return run((ids) => changeStatus(ids.projectId, ids.requirementId, status));
}

function saveAssignee(): Promise<void> {
  const userId = assigneeSelection.value;
  if (userId === null) {
    return Promise.resolve();
  }
  return run((ids) => assign(ids.projectId, ids.requirementId, userId));
}

function saveReviewer(): Promise<void> {
  return run((ids) => assignReviewer(ids.projectId, ids.requirementId, reviewerSelection.value));
}
</script>

<template>
  <section class="requirement-detail-page" aria-labelledby="requirement-title">
    <div class="page-head">
      <p class="eyebrow">
        {{ project ? `${project.name} · REQ-${requirementId ?? "—"}` : "Requirement" }}
      </p>
      <h1 id="requirement-title">
        {{ detail ? detail.currentRevision.title : "需求详情" }}
      </h1>
      <div v-if="projectId !== null" class="record-actions">
        <RouterLink class="button button-quiet" :to="requirementsRoute(projectId)">
          返回需求列表
        </RouterLink>
      </div>
      <p class="lede">查看当前需求契约、人工状态、负责人和每次发布后永久保留的版本链。</p>
    </div>

    <p v-if="!hasContext" class="alert" role="alert">
      需求详情需要项目上下文，请从需求列表进入。
    </p>
    <p v-else-if="loading" class="muted">正在加载需求…</p>
    <p v-else-if="loadError" class="alert" role="alert">{{ loadError }}</p>

    <template v-if="detail !== null">
      <section v-if="returnedReviews.length || returnsError" class="panel returned-reviews" aria-labelledby="returned-reviews-title">
        <h2 id="returned-reviews-title" class="panel-title">退回修改</h2>
        <p>请由开发负责人 {{ detail.assigneeUsername ?? "（待指派）" }} 修改原 PR，并提交新代码后复审。</p>
        <p v-if="returnsError" class="alert" role="alert">退回理由加载失败：{{ returnsError }}</p>
        <article v-for="review in returnedReviews" :key="review.id">
          <RouterLink v-if="projectId !== null" :to="reviewDetailRoute(projectId, review.id)">查看审查记录 {{ review.id }}</RouterLink>
          <p class="muted">{{ members.find(member => member.userId === review.decisionBy)?.displayName ?? `成员 #${review.decisionBy}` }} · {{ review.decisionAt ? formatDateTime(review.decisionAt) : "" }}</p>
          <p class="decision-reason">{{ review.decisionComment || "未填写理由" }}</p>
        </article>
      </section>
      <div class="requirement-overview-grid">
      <div class="panel requirement-overview">
        <h2 class="panel-title">需求概览</h2>
        <dl class="meta-list">
          <div>
            <dt>当前阶段</dt>
            <dd class="requirement-status">
              <span :class="['badge', `badge-${REQUIREMENT_STATUS_TONES[detail.status]}`]">
                {{ requirementPhase(detail.status, reviewActivity?.activity) }}
              </span>
            </dd>
          </div>
          <div>
            <dt>当前处理人</dt>
            <dd>{{ requirementHandler(detail.status, reviewActivity?.activity, detail.assigneeUsername, detail.reviewerName) }}</dd>
          </div>
          <div>
            <dt>开发负责人</dt>
            <dd>{{ detail.assigneeUsername ?? "未指派" }}</dd>
          </div>
          <div>
            <dt>审查人</dt>
            <dd>{{ detail.reviewerName ?? "项目负责人处理" }}</dd>
          </div>
          <div>
            <dt>需求生命周期</dt><dd>{{ REQUIREMENT_STATUS_LABELS[detail.status] }}</dd>
          </div>
          <div>
            <dt>评审活动</dt>
            <dd class="review-activity">
              <span
                v-if="reviewActivity"
                :class="['badge', `badge-${REVIEW_ACTIVITY_TONES[reviewActivity.activity]}`]"
              >
                {{ REVIEW_ACTIVITY_LABELS[reviewActivity.activity] }}
              </span>
              <span v-else class="badge badge-neutral">未返回</span>
            </dd>
          </div>
          <div>
            <dt>当前版本</dt>
            <dd>v{{ detail.currentRevision.seq }}</dd>
          </div>
          <div>
            <dt>创建时间</dt>
            <dd>{{ formatDateTime(detail.createdAt) }}</dd>
          </div>
          <div>
            <dt>更新时间</dt>
            <dd>{{ formatDateTime(detail.updatedAt) }}</dd>
          </div>
        </dl>
        <div v-if="isLeader && detail.status === 'CANCELED'" class="record-actions">
          <button
            type="button"
            class="button button-quiet"
            :disabled="actionPending"
            @click="removeRequirement"
          >删除该作废需求</button>
        </div>
        <p v-if="isLeader && detail.status === 'CANCELED'" class="field-hint">
          删除后它从需求列表消失；AI 调用审计与已发生的 PR 关联记录仍然保留。
        </p>
      </div>

      <section class="panel current-revision" aria-labelledby="current-revision-title">
        <div class="section-action-head">
          <div>
            <p class="eyebrow">Structured requirement</p>
            <h2 id="current-revision-title" class="panel-title">结构化需求</h2>
          </div>
          <button type="button" class="button button-quiet" @click="exportRequirement">
            导出 Markdown
          </button>
        </div>
        <p class="muted">背景：{{ detail.currentRevision.background ?? "未填写" }}</p>
        <p class="muted">描述：{{ detail.currentRevision.description ?? "未填写" }}</p>
        <ol class="criteria-list">
          <li v-for="criterion in detail.currentRevision.acceptanceCriteria" :key="criterion.id">
            <span class="badge badge-neutral">{{ criterion.acKey }}</span>
            {{ criterion.text }}
          </li>
        </ol>
      </section>
      </div>

      <section v-if="reviewActivity" class="panel activity-section" aria-labelledby="activity-title">
        <h2 id="activity-title" class="panel-title">关联 PR 活动分布</h2>
        <p class="field-hint">需求状态由人维护；这里是 PR 与 Review 的只读派生量。</p>
        <dl class="activity-counts">
          <div v-for="state in PULL_REQUEST_ACTIVITIES" :key="state">
            <dt>{{ PULL_REQUEST_ACTIVITY_LABELS[state] }}</dt>
            <dd>{{ reviewActivity.counts[state] }}</dd>
          </div>
        </dl>
      </section>

      <section
        v-if="coverage && coverage.criteria.length > 0"
        class="panel coverage-section"
        aria-labelledby="coverage-title"
      >
        <h2 id="coverage-title" class="panel-title">验收条件覆盖度</h2>
        <p class="field-hint">
          当前修订的每条验收条件在最近一次审查里的结论。发布新修订后会回到「尚未审查」——
          旧裁定针对的是另一套验收条件。
        </p>
        <ul class="coverage-list">
          <li v-for="row in coverage.criteria" :key="row.acKey" class="coverage-row">
            <span class="coverage-key">{{ row.acKey }}</span>
            <span class="coverage-text">{{ row.text }}</span>
            <span
              v-if="row.verdict"
              :class="['badge', `badge-${AC_VERDICT_TONES[row.verdict]}`]"
            >
              {{ AC_VERDICT_LABELS[row.verdict] }}
            </span>
            <span v-else class="badge badge-info">尚未审查</span>
            <span v-if="row.openFindings > 0" class="badge badge-warning">
              {{ row.openFindings }} 条未决
            </span>
          </li>
        </ul>
      </section>

      <section class="panel attachment-section" aria-labelledby="attachment-title">
        <div class="section-action-head">
          <div>
            <p class="eyebrow">Requirement document</p>
            <h2 id="attachment-title" class="panel-title">需求文档</h2>
          </div>
          <span class="badge badge-info">当前需求私有</span>
        </div>
        <p class="field-hint">
          项目成员可阅读和下载；AI 实现建议会召回本需求文档的相关片段。
        </p>
        <form
          v-if="isLeader"
          class="inline-form attachment-form"
          @submit.prevent="uploadSelectedAttachment"
        >
          <div class="field">
            <label for="attachment-file">上传 .txt 或 .md 文档</label>
            <input
              id="attachment-file"
              type="file"
              accept=".txt,.md,text/plain,text/markdown"
              @change="pickAttachment"
            />
            <p v-if="attachmentFile" class="field-hint">已选择：{{ attachmentFile.name }}</p>
          </div>
          <button
            class="button button-primary"
            :disabled="attachmentFile === null || attachmentPending"
          >
            {{ attachmentPending ? "正在上传…" : "上传文档" }}
          </button>
        </form>
        <p v-if="attachmentError" class="alert" role="alert">{{ attachmentError }}</p>
        <p v-if="attachmentPollingError" class="alert" role="alert">
          文档状态自动刷新失败：{{ attachmentPollingError }}
          <button type="button" class="button button-quiet" @click="attachmentPolling.retry">
            重新刷新
          </button>
        </p>
        <p v-if="attachments.length === 0" class="empty-state">该需求还没有文档。</p>
        <ol v-else class="record-list document-list">
          <li v-for="item in attachments" :key="item.id" class="record">
            <div class="record-head">
              <h3 class="record-title">{{ item.title }}</h3>
              <span
                class="badge"
                :class="item.status === 'READY' ? 'badge-success' : item.status === 'FAILED' ? 'badge-danger' : 'badge-warning'"
              >{{ knowledgeStatusLabel(item.status) }}</span>
            </div>
            <p class="muted">
              {{ item.embeddedChunkCount }}/{{ item.chunkCount }} 向量 Chunk · 维度
              {{ item.embeddingDimension ?? "未就绪" }} ·
              {{ [item.embeddingProvider, item.embeddingModel].filter(Boolean).join(" · ") || "未记录 Profile" }}
            </p>
            <p v-if="item.failureReason" class="alert" role="alert">{{ item.failureReason }}</p>
            <div class="record-actions">
              <button
                type="button"
                class="button button-quiet"
                :disabled="documentPending"
                @click="viewAttachment(item.id)"
              >
                查看原文
              </button>
              <a class="button button-quiet" :href="downloadUrl(item.id)">下载</a>
              <button
                v-if="isLeader"
                type="button"
                class="button button-quiet"
                :disabled="attachmentPending"
                @click="promote(item.id)"
              >
                提升为项目知识
              </button>
            </div>
          </li>
        </ol>
        <p v-if="documentPending" class="muted">正在读取文档…</p>
        <p v-if="documentError" class="alert" role="alert">{{ documentError }}</p>
        <section
          v-if="selectedDocument"
          class="document-reader"
          aria-labelledby="document-reader-title"
        >
          <div class="record-head">
            <h3 id="document-reader-title" class="record-title">{{ selectedDocument.fileName }}</h3>
            <span class="badge badge-neutral">{{ selectedDocument.mediaType }}</span>
          </div>
          <pre>{{ selectedDocument.text }}</pre>
        </section>
      </section>
      <section class="ai-assistance" aria-labelledby="ai-assistance-title"><div class="ai-assistance-heading"><p class="eyebrow">AI development assistance</p><h2 id="ai-assistance-title">AI 研发辅助</h2><p>AI 提供一次性分析与知识证据，不会自动改变需求、代码或人工决定。</p></div>
      <div class="requirement-intelligence-grid">
        <section class="panel quality-section" aria-labelledby="quality-title">
          <div class="section-action-head">
            <div>
              <p class="eyebrow">Revision advice</p>
              <h2 id="quality-title" class="panel-title">需求质量检查</h2>
            </div>
            <button v-if="canCheckQuality" type="button" class="button button-primary" :disabled="qualityPending" @click="runQualityCheck">运行检查</button>
          </div>
          <p class="field-hint">结果只属于当前需求版本，是建议而不是状态门禁；草稿内容变化后旧结果会失效。</p>
          <p v-if="!canCheckQuality" class="empty-state">只有项目负责人可以运行质量检查。</p>
          <p v-if="qualityError" class="alert" role="alert">{{ qualityError }}</p>
          <div v-if="qualityReport" class="quality-report">
            <dl class="meta-list">
              <div><dt>检查版本</dt><dd>v{{ qualityReport.revisionSeq }}</dd></div>
              <div><dt>规则集</dt><dd>{{ qualityReport.qualityVersion }}</dd></div>
              <div><dt>检查时间</dt><dd>{{ formatDateTime(qualityReport.checkedAt) }}</dd></div>
            </dl>
            <h3 class="subsection-title">确定性规则</h3>
            <p v-if="qualityReport.rules.length === 0" class="muted">规则没有发现问题。</p>
            <ul v-else class="advice-list">
              <li v-for="(rule, index) in qualityReport.rules" :key="index"><span class="badge badge-warning">{{ rule.rule }}</span> <span v-if="rule.acKey" class="badge badge-neutral">{{ rule.acKey }}</span> {{ rule.message }}</li>
            </ul>
            <h3 class="subsection-title">AI 结构化评估</h3>
            <p v-if="qualityReport.ai === null" class="muted">本次没有返回 AI 评估。</p>
            <template v-else>
              <p class="muted advice-prose">{{ qualityReport.ai.summary ?? "AI 没有给出总结。" }}</p>
              <p v-if="qualityReport.ai.issues.length === 0" class="muted">AI 没有发现问题。</p>
              <ul v-else class="advice-list">
                <li v-for="(issue, index) in qualityReport.ai.issues" :key="index"><span v-if="issue.acKey" class="badge badge-neutral">{{ issue.acKey }}</span> {{ issue.message }}</li>
              </ul>
            </template>
          </div>
        </section>

        <section class="panel guidance-section" aria-labelledby="guidance-title">
          <div class="section-action-head">
            <div><p class="eyebrow">开发准备</p><h2 id="guidance-title" class="panel-title">实现建议</h2></div>
            <button v-if="canGenerateGuidance" type="button" class="button button-primary" :disabled="!canRunGuidance" @click="runGuidance">
              {{ guidancePending ? "正在生成…" : guidance ? "重新生成" : "生成建议" }}
            </button>
          </div>
          <p class="field-hint">基于已保存的需求与项目知识，不读取未保存内容或仓库源码，不会自动修改代码或需求状态。刷新后建议不保留，可先复制或下载。</p>
          <p v-if="!canGenerateGuidance" class="empty-state">项目负责人或该需求已指派的开发可以生成实现建议。</p>
          <p v-if="canGenerateGuidance && hasUnsavedContent" class="field-hint guidance-unsaved">
            请先{{ isDraft ? "保存草稿" : "发布修订" }}再生成；AI 不读取未保存内容。已有建议仍只针对已保存版本。
          </p>
          <p v-if="guidancePending" class="field-hint" role="status">
            正在结合已保存的需求和项目知识生成建议，请稍候。{{ guidance ? "以下仍为上一份建议。" : "" }}
          </p>
          <p v-if="guidanceError" class="alert" role="alert">{{ guidanceError }}</p>
          <p v-if="guidanceError && guidance" class="field-hint">新建议生成失败，以下仍为上一份有效建议。</p>
          <div v-if="guidance" class="guidance-result">
            <p class="muted">基于已保存的需求 v{{ guidance.revisionSeq }}；请结合实际代码核对后使用。</p>
            <div class="form-actions">
              <button type="button" class="button button-quiet" data-guidance-copy @click="copyGuidance">复制建议</button>
              <button type="button" class="button button-quiet" data-guidance-download @click="exportGuidance">下载 Markdown</button>
            </div>
            <p v-if="guidanceCopyFeedback" class="field-hint" role="status">{{ guidanceCopyFeedback }}</p>

            <template v-if="guidance.summary !== undefined">
              <h3 class="subsection-title">建议概览</h3>
              <p class="advice-prose">{{ guidance.summary }}</p>
            </template>
            <template v-if="guidance.questions !== undefined">
              <h3 class="subsection-title">开始前需确认</h3>
              <p v-if="guidance.questions.length === 0" class="muted">本次未提出待确认事项，请自行核对需求是否完整。</p>
              <ul v-else class="advice-list"><li v-for="(item, index) in guidance.questions" :key="index" class="advice-prose">{{ item }}</li></ul>
            </template>
            <h3 class="subsection-title">实施清单</h3>
            <p v-if="guidance.checklist.length === 0" class="muted">本次未提供可直接执行的步骤，请先核对需求信息。</p>
            <ol v-else class="advice-list guidance-steps"><li v-for="(item, index) in guidance.checklist" :key="index" class="advice-prose">{{ item }}</li></ol>
            <h3 class="subsection-title">项目规则</h3>
            <p v-if="guidance.rules.length === 0" class="muted">本次未提出额外规则。</p>
            <ul v-else class="advice-list"><li v-for="(item, index) in guidance.rules" :key="index" class="advice-prose">{{ item }}</li></ul>
            <h3 class="subsection-title">风险提示</h3>
            <p v-if="guidance.risks.length === 0" class="muted">本次未提出明确风险，不代表已确认无风险。</p>
            <ul v-else class="advice-list"><li v-for="(item, index) in guidance.risks" :key="index" class="advice-prose">{{ item }}</li></ul>

            <details class="guidance-sources">
              <summary>本次参考资料（{{ guidance.knowledgeSources.length }} 条）</summary>
              <p class="field-hint">这些资料实际参与了本次生成，不代表每条建议都已得到验证。检索相似度表示相关程度，不是建议正确率。</p>
              <p v-if="guidance.knowledgeSources.length === 0" class="muted">本次未召回知识资料。</p>
              <ul v-else class="advice-list">
                <li v-for="source in guidance.knowledgeSources" :key="`${source.documentId}-${source.chunkSeq}`">
                  <strong>{{ source.title }}</strong> <span class="muted">检索相似度 {{ source.similarity.toFixed(3) }}</span>
                  <p class="muted advice-prose">{{ source.excerpt }}</p>
                </li>
              </ul>
            </details>
            <details class="guidance-metadata">
              <summary>版本详情</summary>
              <p class="muted">需求修订 ID {{ guidance.revisionId }} · 建议版本 {{ guidance.guidanceVersion ?? "未记录（旧版服务）" }}</p>
            </details>
          </div>
        </section>
      </div>
      </section>

      <div class="requirement-edit-grid">
      <section v-if="editable" class="panel requirement-actions" aria-labelledby="requirement-actions-title">
        <h2 id="requirement-actions-title" class="panel-title">状态与指派</h2>

        <div class="form-actions">
          <button
            v-for="next in STATUS_TRANSITIONS[detail.status]"
            :key="next"
            type="button"
            class="button button-quiet"
            :disabled="actionPending"
            @click="transitionTo(next)"
          >
            置为 {{ REQUIREMENT_STATUS_LABELS[next] }}
          </button>
        </div>
        <p class="field-hint">「开发中」只能由首次指派触发，不在状态按钮中提供。</p>

        <form class="inline-form reviewer-form" @submit.prevent="saveReviewer">
          <div class="field">
            <label for="requirement-reviewer">审查负责人</label>
            <select id="requirement-reviewer" v-model="reviewerSelection">
              <option :value="null">未指定，由项目负责人处理</option>
              <option v-for="member in reviewerCandidates" :key="member.userId" :value="member.userId">{{ member.displayName }}（{{ member.username }}）</option>
            </select>
          </div>
          <button type="submit" class="button button-quiet" :disabled="actionPending">保存审查人</button>
        </form>

        <form class="inline-form assignee-form" @submit.prevent="saveAssignee">
          <div class="field">
            <label for="requirement-assignee">开发负责人</label>
            <select id="requirement-assignee" v-model="assigneeSelection">
              <option :value="null" disabled>请选择成员</option>
              <option v-for="member in developerCandidates" :key="member.userId" :value="member.userId">
                {{ member.username }}
              </option>
            </select>
          </div>
          <button type="submit" class="button button-quiet" :disabled="actionPending">
            保存指派
          </button>
        </form>
      </section>

      <form v-if="editable" class="panel requirement-form requirement-editor" @submit.prevent="saveContent">
        <h2 class="panel-title">{{ isDraft ? "编辑草稿" : "发布新版本" }}</h2>
        <div class="field">
          <label for="edit-title">标题</label>
          <input id="edit-title" v-model="draftTitle" required maxlength="200" />
        </div>
        <div class="field">
          <label for="edit-background">背景</label>
          <textarea id="edit-background" v-model="draftBackground" rows="3"></textarea>
        </div>
        <div class="field">
          <label for="edit-description">描述</label>
          <textarea id="edit-description" v-model="draftDescription" rows="4"></textarea>
        </div>
        <AcceptanceCriteriaEditor v-model="draftCriteria" id-prefix="edit" />
        <div v-if="!isDraft" class="field">
          <label for="edit-change-reason">变更原因</label>
          <input id="edit-change-reason" v-model="changeReason" required maxlength="200" />
        </div>
        <div class="form-actions">
          <button type="submit" class="button button-primary" :disabled="actionPending">
            {{ isDraft ? "保存草稿" : "发布新版本" }}
          </button>
        </div>
      </form>
      </div>

      <p v-if="actionError" class="alert" role="alert">{{ actionError }}</p>

      <section class="panel revision-history" aria-labelledby="revision-history-title">
        <h2 id="revision-history-title" class="panel-title">版本历史</h2>
        <ol class="revision-list">
          <li v-for="revision in revisions" :key="revision.id" class="revision">
            <div class="record-head">
              <h3 class="record-title">v{{ revision.seq }} · {{ revision.title }}</h3>
              <span class="badge badge-neutral">{{ revision.createdByUsername }}</span>
            </div>
            <p class="muted">
              {{ formatDateTime(revision.createdAt) }} · 变更原因：{{
                revision.changeReason ?? "首个版本"
              }}
            </p>
            <ol class="criteria-list">
              <li v-for="criterion in revision.acceptanceCriteria" :key="criterion.id">
                <span class="badge badge-neutral">{{ criterion.acKey }}</span>
                {{ criterion.text }}
              </li>
            </ol>
          </li>
        </ol>
      </section>
    </template>
  </section>
</template>

<style scoped>
.decision-reason { white-space: pre-wrap; overflow-wrap: anywhere; }

.requirement-overview-grid,
.requirement-edit-grid,
.requirement-intelligence-grid {
  display: grid;
  align-items: start;
  gap: var(--fp-space-6);
  grid-template-columns: minmax(18rem, 0.7fr) minmax(0, 1.3fr);
}

.requirement-intelligence-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.requirement-overview-grid {
  grid-template-areas: "overview current";
}

.requirement-overview {
  grid-area: overview;
}

.current-revision {
  grid-area: current;
}

.requirement-overview,
.current-revision,
.requirement-actions,
.requirement-editor {
  height: 100%;
}

.current-revision {
  border-color: var(--fp-color-border-accent);
}

.quality-section,
.guidance-section {
  border-color: var(--fp-color-border-accent);
}

.attachment-section { border-color: var(--fp-color-border-accent); }
.attachment-form { margin-top: var(--fp-space-5); }
.ai-assistance { margin-bottom: var(--fp-space-6); }
.ai-assistance-heading { margin-bottom: var(--fp-space-4); padding: var(--fp-space-5); border-left: 0.1875rem solid var(--fp-color-accent); background: var(--fp-color-accent-soft); }
.ai-assistance-heading h2,.ai-assistance-heading p { margin: 0; }
.ai-assistance-heading p:last-child { margin-top: var(--fp-space-2); color: var(--fp-color-text-muted); }

.section-action-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--fp-space-4);
  margin-bottom: var(--fp-space-3);
}

.section-action-head .eyebrow {
  margin-bottom: var(--fp-space-2);
}

.section-action-head .panel-title {
  margin-bottom: 0;
}

/*
 * 长输出定高滚动：沿用 FindingCard 的 `.narrative-body` 那一组属性（定高 +
 * overflow + break-word），不另造原语。落在结果区整体而不是逐块，避免
 * 「清单滚动条套在结果滚动条里」的嵌套滚动。word-break 会继承，因此内部
 * 列表项的超长 token 也一起受约束。
 */
.quality-report,
.guidance-result {
  max-height: 32rem;
  margin-top: var(--fp-space-5);
  overflow: auto;
  word-break: break-word;
}

/* 只加在真正承载模型多行散文的节点上；加到 <ul>/<ol> 会把模板缩进渲染成空行。 */
.advice-prose {
  white-space: pre-wrap;
}

.subsection-title {
  margin: var(--fp-space-5) 0 var(--fp-space-2);
  font-size: 0.9375rem;
}

.advice-list {
  display: grid;
  gap: var(--fp-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
  line-height: 1.6;
}

.guidance-steps {
  list-style: decimal;
  padding-inline-start: var(--fp-space-6);
}

.guidance-sources,
.guidance-metadata {
  margin-top: var(--fp-space-5);
}

.document-reader {
  margin-top: var(--fp-space-5);
}

.document-reader pre,
.guidance-result pre {
  max-height: 30rem;
  margin: var(--fp-space-3) 0 0;
  padding: var(--fp-space-4);
  overflow: auto;
  border: 0.0625rem solid var(--fp-color-border);
  border-left: 0.1875rem solid var(--fp-color-accent);
  border-radius: var(--fp-radius-sm);
  background: var(--fp-color-canvas-muted);
  color: var(--fp-color-text);
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.activity-counts {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: var(--fp-space-3);
  margin: var(--fp-space-4) 0 0;
}

.activity-counts > div {
  padding: var(--fp-space-3);
  border: 0.0625rem solid var(--fp-color-border);
  border-radius: var(--fp-radius-sm);
  background: var(--fp-color-canvas-muted);
}

.activity-counts dt {
  color: var(--fp-color-text-muted);
  font-size: 0.75rem;
}

.activity-counts dd {
  margin: var(--fp-space-1) 0 0;
  color: var(--fp-color-accent-inverse);
  font: 800 1.25rem/1 var(--fp-font-mono);
}

.coverage-list {
  display: flex;
  flex-direction: column;
  gap: var(--fp-space-2);
  margin: var(--fp-space-4) 0 0;
  padding: 0;
  list-style: none;
}

.coverage-row {
  display: flex;
  align-items: center;
  gap: var(--fp-space-3);
  padding: var(--fp-space-3);
  border: 0.0625rem solid var(--fp-color-border);
  border-radius: var(--fp-radius-sm);
  background: var(--fp-color-canvas-muted);
}

.coverage-key {
  flex: 0 0 auto;
  color: var(--fp-color-text-muted);
  font: 700 0.75rem/1.4 var(--fp-font-mono);
}

/* 条文占据剩余宽度，把两个徽章推到行尾对齐。 */
.coverage-text {
  flex: 1 1 auto;
  min-width: 0;
}

.requirement-actions {
  position: sticky;
  top: 6rem;
}

.requirement-form {
  display: grid;
  gap: var(--fp-space-5);
}

.criteria-list {
  display: grid;
  gap: var(--fp-space-2);
  margin: var(--fp-space-3) 0 0;
  padding-left: var(--fp-space-6);
}

.revision-list {
  display: grid;
  gap: var(--fp-space-4);
  margin: var(--fp-space-3) 0 0;
  padding: 0;
  list-style: none;
}

.revision {
  padding-top: var(--fp-space-4);
  border-top: 0.0625rem solid var(--fp-color-border);
}

.revision:first-child {
  padding-top: 0;
  border-top: 0;
}

.revision-history {
  margin-top: 0;
}

@media (max-width: 64rem) {
  .requirement-overview-grid,
  .requirement-edit-grid,
  .requirement-intelligence-grid {
    grid-template-columns: 1fr;
  }

  .requirement-overview-grid {
    grid-template-areas:
      "overview"
      "current";
  }

  .activity-counts {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .requirement-actions {
    position: static;
  }
}

@media (max-width: 42rem) {
  .section-action-head {
    flex-direction: column;
  }

  .activity-counts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
