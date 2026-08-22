<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { RouterLink, useRoute } from "vue-router";

import { parseId, requirementsRoute, PROJECT_QUERY_KEY } from "../../app/routes";
import { formatDateTime } from "../../lib/datetime";
import { apiErrorMessage } from "../../lib/http";
import { getProject, listMembers, type Member, type Project } from "../project/api";
import AcceptanceCriteriaEditor from "./AcceptanceCriteriaEditor.vue";
import {
  assign,
  changeStatus,
  editDraft,
  getRequirement,
  listRevisions,
  publishRevision,
  toDraft,
  type AcceptanceCriterionDraft,
  type RequirementDetail,
  type Revision,
  type RevisionContent,
} from "./api";
import {
  isTerminal,
  REQUIREMENT_STATUS_LABELS,
  REQUIREMENT_STATUS_TONES,
  STATUS_TRANSITIONS,
  type RequirementStatus,
} from "./status";

const route = useRoute();

const projectId = computed(() => parseId(route.query[PROJECT_QUERY_KEY]));
const requirementId = computed(() => parseId(route.params.id));

const project = ref<Project | null>(null);
const members = ref<Member[]>([]);
const detail = ref<RequirementDetail | null>(null);
const revisions = ref<Revision[]>([]);
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

const isLeader = computed(() => project.value?.myRole === "LEADER");
const isDraft = computed(() => detail.value?.status === "DRAFT");
const editable = computed(
  () => isLeader.value && detail.value !== null && !isTerminal(detail.value.status),
);

function target(): { projectId: number; requirementId: number } | null {
  const pid = projectId.value;
  const rid = requirementId.value;
  return pid === null || rid === null ? null : { projectId: pid, requirementId: rid };
}

const hasContext = computed(() => target() !== null);

function applyDetail(loaded: RequirementDetail): void {
  detail.value = loaded;
  assigneeSelection.value = loaded.assigneeId;
  const content = toDraft(loaded.currentRevision);
  draftTitle.value = content.title;
  draftBackground.value = content.background ?? "";
  draftDescription.value = content.description ?? "";
  draftCriteria.value = content.acceptanceCriteria;
  changeReason.value = "";
}

onMounted(async () => {
  const ids = target();
  if (ids === null) {
    loading.value = false;
    return;
  }
  try {
    const [loadedProject, loadedMembers, loadedDetail, loadedRevisions] = await Promise.all([
      getProject(ids.projectId),
      listMembers(ids.projectId),
      getRequirement(ids.projectId, ids.requirementId),
      listRevisions(ids.projectId, ids.requirementId),
    ]);
    project.value = loadedProject;
    members.value = loadedMembers;
    revisions.value = loadedRevisions;
    applyDetail(loadedDetail);
  } catch (failure: unknown) {
    loadError.value = apiErrorMessage(failure);
  } finally {
    loading.value = false;
  }
});

async function run(
  action: (ids: { projectId: number; requirementId: number }) => Promise<RequirementDetail>,
): Promise<void> {
  const ids = target();
  if (ids === null) {
    return;
  }
  actionPending.value = true;
  actionError.value = null;
  try {
    applyDetail(await action(ids));
    revisions.value = await listRevisions(ids.projectId, ids.requirementId);
  } catch (failure: unknown) {
    actionError.value = apiErrorMessage(failure);
  } finally {
    actionPending.value = false;
  }
}

function saveContent(): Promise<void> {
  const content: RevisionContent = {
    title: draftTitle.value,
    background: draftBackground.value === "" ? null : draftBackground.value,
    description: draftDescription.value === "" ? null : draftDescription.value,
    acceptanceCriteria: draftCriteria.value,
  };
  return run((ids) =>
    isDraft.value
      ? editDraft(ids.projectId, ids.requirementId, content)
      : publishRevision(ids.projectId, ids.requirementId, content, changeReason.value),
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
  <section aria-labelledby="requirement-title">
    <div class="page-head">
      <p class="eyebrow">{{ project ? project.name : "Requirement" }}</p>
      <h1 id="requirement-title">
        {{ detail ? detail.currentRevision.title : "需求详情" }}
      </h1>
      <div v-if="projectId !== null" class="record-actions">
        <RouterLink class="button button-quiet" :to="requirementsRoute(projectId)">
          返回需求列表
        </RouterLink>
      </div>
    </div>

    <p v-if="!hasContext" class="alert" role="alert">
      需求详情需要项目上下文，请从需求列表进入。
    </p>
    <p v-else-if="loading" class="muted">正在加载需求…</p>
    <p v-else-if="loadError" class="alert" role="alert">{{ loadError }}</p>

    <template v-if="detail !== null">
      <div class="panel">
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

      <section class="panel" aria-labelledby="current-revision-title">
        <h2 id="current-revision-title" class="panel-title">当前版本内容</h2>
        <p class="muted">背景：{{ detail.currentRevision.background ?? "未填写" }}</p>
        <p class="muted">描述：{{ detail.currentRevision.description ?? "未填写" }}</p>
        <ol class="criteria-list">
          <li v-for="criterion in detail.currentRevision.acceptanceCriteria" :key="criterion.id">
            <span class="badge badge-neutral">{{ criterion.acKey }}</span>
            {{ criterion.text }}
          </li>
        </ol>
      </section>

      <section v-if="editable" class="panel" aria-labelledby="requirement-actions-title">
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

      <form v-if="editable" class="panel requirement-form" @submit.prevent="saveContent">
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

      <p v-if="actionError" class="alert" role="alert">{{ actionError }}</p>

      <section class="panel" aria-labelledby="revision-history-title">
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
.requirement-form {
  display: grid;
  gap: var(--fp-space-4);
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
</style>
