<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink, useRoute } from "vue-router";

import { parseId, requirementsRoute, PROJECT_QUERY_KEY } from "../../app/routes";
import { formatDateTime } from "../../lib/datetime";
import { apiErrorMessage } from "../../lib/http";
import { useSession } from "../auth/session";
import { getProject, hasProjectRole, listMembers, type Member, type Project } from "../project/api";
import {
  getRequirementReviewActivity,
  type ActivityView,
} from "../review/api";
import {
  PULL_REQUEST_ACTIVITIES,
  PULL_REQUEST_ACTIVITY_LABELS,
  REVIEW_ACTIVITY_LABELS,
  REVIEW_ACTIVITY_TONES,
} from "../review/labels";
import AcceptanceCriteriaEditor from "./AcceptanceCriteriaEditor.vue";
import {
  attachmentDownloadUrl,
  assign,
  checkQuality,
  changeStatus,
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
import type { KnowledgeDocument } from "../knowledge/api";
import {
  isTerminal,
  REQUIREMENT_STATUS_LABELS,
  REQUIREMENT_STATUS_TONES,
  STATUS_TRANSITIONS,
  type RequirementStatus,
} from "./status";

const route = useRoute();
const { account } = useSession();

const projectId = computed(() => parseId(route.query[PROJECT_QUERY_KEY]));
const requirementId = computed(() => parseId(route.params.id));

const project = ref<Project | null>(null);
const members = ref<Member[]>([]);
const detail = ref<RequirementDetail | null>(null);
const revisions = ref<Revision[]>([]);
const reviewActivity = ref<ActivityView | null>(null);
const loading = ref(true);
const loadError = ref<string | null>(null);

const draftTitle = ref("");
const draftBackground = ref("");
const draftDescription = ref("");
const draftCriteria = ref<AcceptanceCriterionDraft[]>([]);
const changeReason = ref("");
const assigneeSelection = ref<number | null>(null);

const actionError = ref<string | null>(null);
const actionPending = ref(false);

const qualityReport = ref<QualityReport | null>(null);
const qualityPending = ref(false);
const qualityError = ref<string | null>(null);
const guidance = ref<ImplementationGuidance | null>(null);
const guidancePending = ref(false);
const guidanceError = ref<string | null>(null);
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

function target(): { projectId: number; requirementId: number } | null {
  const pid = projectId.value;
  const rid = requirementId.value;
  return pid === null || rid === null ? null : { projectId: pid, requirementId: rid };
}

const hasContext = computed(() => target() !== null);

function applyDetail(loaded: RequirementDetail): void {
  if (detail.value?.currentRevision.id !== loaded.currentRevision.id) {
    qualityReport.value = null;
    guidance.value = null;
  }
  detail.value = loaded;
  assigneeSelection.value = loaded.assigneeId;
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
  qualityReport.value = null;
  guidance.value = null;
  attachments.value = [];
  selectedDocument.value = null;
  documentError.value = null;
  if (ids === null) {
    loading.value = false;
    return;
  }
  try {
    const [loadedProject, loadedMembers, loadedDetail, loadedRevisions, loadedActivity, loadedAttachments] =
      await Promise.all([
        getProject(ids.projectId),
        listMembers(ids.projectId),
        getRequirement(ids.projectId, ids.requirementId),
        listRevisions(ids.projectId, ids.requirementId),
        getRequirementReviewActivity(ids.projectId, ids.requirementId),
        listAttachments(ids.projectId, ids.requirementId),
      ]);
    if (token !== detailLoadToken) {
      return;
    }
    project.value = loadedProject;
    members.value = loadedMembers;
    revisions.value = loadedRevisions;
    reviewActivity.value = loadedActivity;
    attachments.value = loadedAttachments;
    applyDetail(loadedDetail);
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
      guidance.value = null;
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
  if (ids === null || !canGenerateGuidance.value) {
    return;
  }
  guidancePending.value = true;
  guidanceError.value = null;
  try {
    guidance.value = await generateGuidance(ids.projectId, ids.requirementId);
  } catch (failure: unknown) {
    guidanceError.value = apiErrorMessage(failure);
  } finally {
    guidancePending.value = false;
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

  const url = URL.createObjectURL(new Blob([`${sections.join("\n\n")}\n`], {
    type: "text/markdown;charset=utf-8",
  }));
  const link = document.createElement("a");
  link.href = url;
  link.download = `REQ-${loaded.id}-v${revision.seq}.md`;
  link.click();
  URL.revokeObjectURL(url);
}

function saveContent(): Promise<void> {
  const content: RevisionContent = {
    title: draftTitle.value,
    background: draftBackground.value === "" ? null : draftBackground.value,
    description: draftDescription.value === "" ? null : draftDescription.value,
    acceptanceCriteria: draftCriteria.value,
  };
  return run(
    (ids) =>
      isDraft.value
        ? editDraft(ids.projectId, ids.requirementId, content)
        : publishRevision(ids.projectId, ids.requirementId, content, changeReason.value),
    true,
  );
}

function transitionTo(status: RequirementStatus): Promise<void> {
  return run((ids) => changeStatus(ids.projectId, ids.requirementId, status));
}

function saveAssignee(): Promise<void> {
  const userId = assigneeSelection.value;
  if (userId === null) {
    return Promise.resolve();
  }
  return run((ids) => assign(ids.projectId, ids.requirementId, userId));
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
      <div class="requirement-overview-grid">
      <div class="panel requirement-overview">
        <h2 class="panel-title">需求概览</h2>
        <dl class="meta-list">
          <div>
            <dt>需求状态</dt>
            <dd class="requirement-status">
              <span :class="['badge', `badge-${REQUIREMENT_STATUS_TONES[detail.status]}`]">
                {{ REQUIREMENT_STATUS_LABELS[detail.status] }}
              </span>
            </dd>
          </div>
          <div>
            <dt>负责人</dt>
            <dd>{{ detail.assigneeUsername ?? "未指派" }}</dd>
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
        <p v-if="attachments.length === 0" class="empty-state">该需求还没有文档。</p>
        <ol v-else class="record-list document-list">
          <li v-for="item in attachments" :key="item.id" class="record">
            <div class="record-head">
              <h3 class="record-title">{{ item.title }}</h3>
              <span class="badge badge-info">{{ item.status }}</span>
            </div>
            <p class="muted">
              {{ item.embeddedChunkCount }}/{{ item.chunkCount }} 向量 Chunk · 维度
              {{ item.embeddingDimension ?? "未就绪" }} ·
              {{ [item.embeddingProvider, item.embeddingModel].filter(Boolean).join(" · ") || "未记录 Profile" }}
            </p>
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
            <div><p class="eyebrow">One-shot guidance</p><h2 id="guidance-title" class="panel-title">实现建议</h2></div>
            <button v-if="canGenerateGuidance" type="button" class="button button-primary" :disabled="guidancePending" @click="runGuidance">生成建议</button>
          </div>
          <p class="field-hint">对当前不可变版本生成一次性实现建议；没有对话历史，也不会自动修改需求状态或代码。</p>
          <p v-if="!canGenerateGuidance" class="empty-state">项目负责人或该需求已指派的开发可以生成实现建议。</p>
          <p v-if="guidanceError" class="alert" role="alert">{{ guidanceError }}</p>
          <div v-if="guidance" class="guidance-result"><p class="muted">基于 v{{ guidance.revisionSeq }}（版本 {{ guidance.revisionId }}）与向量召回的项目知识。</p><h3 class="subsection-title">实现清单</h3><p v-if="guidance.checklist.length===0" class="muted">本次没有返回实现清单。</p><ul v-else class="advice-list"><li v-for="(item,index) in guidance.checklist" :key="index">{{item}}</li></ul><h3 class="subsection-title">相关规则</h3><p v-if="guidance.rules.length===0" class="muted">本次没有返回规则。</p><ul v-else class="advice-list"><li v-for="(item,index) in guidance.rules" :key="index">{{item}}</li></ul><h3 class="subsection-title">风险提示</h3><p v-if="guidance.risks.length===0" class="muted">本次没有返回风险提示。</p><ul v-else class="advice-list"><li v-for="(item,index) in guidance.risks" :key="index">{{item}}</li></ul><h3 class="subsection-title">实际召回的知识来源</h3><p v-if="guidance.knowledgeSources.length===0" class="muted">本次没有召回可展示的知识来源。</p><ol v-else class="advice-list"><li v-for="source in guidance.knowledgeSources" :key="`${source.documentId}-${source.chunkSeq}`"><strong>{{source.title}}</strong> <span class="badge badge-info">向量语义召回相似度 {{source.similarity.toFixed(3)}}</span><br /><span class="muted advice-prose">{{source.excerpt}}</span></li></ol></div>
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

        <form class="inline-form" @submit.prevent="saveAssignee">
          <div class="field">
            <label for="requirement-assignee">指派给</label>
            <select id="requirement-assignee" v-model="assigneeSelection">
              <option :value="null" disabled>请选择成员</option>
              <option v-for="member in members" :key="member.userId" :value="member.userId">
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
