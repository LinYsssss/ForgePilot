<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink, useRouter } from "vue-router";

import { requirementDetailRoute } from "../../app/routes";
import { useProjectSelection } from "../../composables/useProjectSelection";
import { formatDateTime } from "../../lib/datetime";
import { apiErrorMessage } from "../../lib/http";
import { listReviewActivity, type ActivityView } from "../review/api";
import {
  REVIEW_ACTIVITIES,
  REVIEW_ACTIVITY_LABELS,
} from "../review/labels";
import AcceptanceCriteriaEditor from "./AcceptanceCriteriaEditor.vue";
import {
  changeStatus,
  createRequirement,
  deleteRequirement,
  listRequirements,
  type AcceptanceCriterionDraft,
  type RequirementSummary,
} from "./api";
import {
  isTerminal,
  requirementPhase,
  requirementHandler,
  REQUIREMENT_STATUS_LABELS,
  REQUIREMENT_STATUSES,
  REQUIREMENT_STATUS_TONES,
  STATUS_TRANSITIONS,
} from "./status";

const router = useRouter();

const {
  projects,
  selectedProjectId: projectId,
  selectedProject,
  isLeader,
  error: loadError,
  select,
} = useProjectSelection({ routeName: "requirements" });
const requirements = ref<RequirementSummary[]>([]);
const activity = ref<Record<string, ActivityView>>({});
const loading = ref(false);
const search = ref("");
const statusFilter = ref("");
const activityFilter = ref("");

const title = ref("");
const background = ref("");
const description = ref("");
const criteria = ref<AcceptanceCriterionDraft[]>([{ text: "" }]);
const creating = ref(false);
const createError = ref<string | null>(null);
let requirementsLoadToken = 0;

const filteredRequirements = computed(() => {
  const query = search.value.trim().toLocaleLowerCase();
  return requirements.value.filter((item) => {
    const reviewActivity = activity.value[String(item.id)]?.activity ?? null;
    return (
      (query === "" ||
        item.title.toLocaleLowerCase().includes(query) ||
        String(item.id).includes(query) ||
        (item.assigneeUsername ?? "").toLocaleLowerCase().includes(query)) &&
      (statusFilter.value === "" || item.status === statusFilter.value) &&
      (activityFilter.value === "" || reviewActivity === activityFilter.value)
    );
  });
});

const selection = computed({
  get: () => projectId.value,
  set: (value: number | null) => {
    void select(value);
  },
});

watch(
  projectId,
  async (id) => {
    const token = ++requirementsLoadToken;
    requirements.value = [];
    activity.value = {};
    if (id === null) {
      return;
    }
    loading.value = true;
    loadError.value = null;
    try {
      const [loadedRequirements, loadedActivity] = await Promise.all([
        listRequirements(id),
        listReviewActivity(id),
      ]);
      if (token !== requirementsLoadToken) {
        return;
      }
      requirements.value = loadedRequirements;
      activity.value = loadedActivity;
    } catch (failure: unknown) {
      if (token === requirementsLoadToken) {
        loadError.value = apiErrorMessage(failure);
      }
    } finally {
      if (token === requirementsLoadToken) {
        loading.value = false;
      }
    }
  },
  { immediate: true },
);

async function create(): Promise<void> {
  const id = projectId.value;
  if (id === null) {
    return;
  }
  creating.value = true;
  createError.value = null;
  try {
    const created = await createRequirement(id, {
      title: title.value,
      background: background.value === "" ? null : background.value,
      description: description.value === "" ? null : description.value,
      acceptanceCriteria: criteria.value,
    });
    await router.push(requirementDetailRoute(id, created.id));
  } catch (failure: unknown) {
    createError.value = apiErrorMessage(failure);
  } finally {
    creating.value = false;
  }
}

/**
 * 行内动作只是把详情页早就有的能力提到列表上，**不放宽任何前置条件**：
 * 正文修改仍然只对 DRAFT（READY 之后必须发布新修订），
 * 删除仍然只对已作废需求，且后端做的是软删。
 */
const rowError = ref<string | null>(null);
const rowPending = ref<number | null>(null);

function canCancel(item: RequirementSummary): boolean {
  return STATUS_TRANSITIONS[item.status].includes("CANCELED");
}

async function cancelRequirement(item: RequirementSummary): Promise<void> {
  const id = projectId.value;
  if (id === null) return;
  if (!window.confirm(`确认作废「${item.title}」？作废后它不再参与开发，可再删除。`)) {
    return;
  }
  rowPending.value = item.id;
  rowError.value = null;
  try {
    const updated = await changeStatus(id, item.id, "CANCELED");
    requirements.value = requirements.value.map((it) =>
      it.id === item.id ? { ...it, status: updated.status } : it,
    );
  } catch (failure: unknown) {
    rowError.value = apiErrorMessage(failure);
  } finally {
    rowPending.value = null;
  }
}

async function removeRequirement(item: RequirementSummary): Promise<void> {
  const id = projectId.value;
  if (id === null) return;
  if (
    !window.confirm(
      `确认删除已作废的「${item.title}」？它将从需求列表中消失，AI 调用审计与 PR 关联记录仍然保留。`,
    )
  ) {
    return;
  }
  rowPending.value = item.id;
  rowError.value = null;
  try {
    await deleteRequirement(id, item.id);
    requirements.value = requirements.value.filter((it) => it.id !== item.id);
  } catch (failure: unknown) {
    rowError.value = apiErrorMessage(failure);
  } finally {
    rowPending.value = null;
  }
}
</script>

<template>
  <section class="requirements-page" aria-labelledby="requirements-title">
    <div class="page-head">
      <h1 id="requirements-title">研发需求</h1>
      <p class="field-hint">需求以不可变版本与稳定 AC 描述“应该做什么”，是后续审查的判断基准。</p>
    </div>

    <section class="panel requirement-toolbar" aria-label="项目与筛选">
      <div class="field toolbar-project">
        <label for="requirement-project">当前项目</label>
        <select id="requirement-project" v-model="selection">
          <option :value="null" disabled>请选择项目</option>
          <option v-for="project in projects" :key="project.id" :value="project.id">
            {{ project.name }}
          </option>
        </select>
      </div>
      <template v-if="projectId !== null && requirements.length > 0">
        <div class="field toolbar-search">
          <label for="requirement-search">搜索</label>
          <input
            id="requirement-search"
            v-model="search"
            type="search"
            placeholder="需求编号、标题或负责人"
          />
        </div>
        <div class="field toolbar-status">
          <label for="requirement-status-filter">需求状态</label>
          <select id="requirement-status-filter" v-model="statusFilter">
            <option value="">全部状态</option>
            <option v-for="status in REQUIREMENT_STATUSES" :key="status" :value="status">
              {{ REQUIREMENT_STATUS_LABELS[status] }}
            </option>
          </select>
        </div>
        <div class="field toolbar-activity">
          <label for="requirement-activity-filter">评审活动</label>
          <select id="requirement-activity-filter" v-model="activityFilter">
            <option value="">全部活动</option>
            <option v-for="state in REVIEW_ACTIVITIES" :key="state" :value="state">
              {{ REVIEW_ACTIVITY_LABELS[state] }}
            </option>
          </select>
        </div>
        <p class="toolbar-summary" role="status">
          筛出 {{ filteredRequirements.length }} / 共 {{ requirements.length }} 条
        </p>
      </template>
    </section>

    <p v-if="projectId === null" class="empty-state">先选择一个项目，再查看它的需求。</p>

    <template v-if="projectId !== null">
      <p v-if="loading" class="muted">正在加载需求…</p>
      <p v-else-if="loadError" class="alert" role="alert">{{ loadError }}</p>
      <p v-else-if="requirements.length === 0" class="empty-state">该项目还没有需求。</p>

      <template v-else>
      <p v-if="rowError" class="alert" role="alert">{{ rowError }}</p>

      <p v-if="filteredRequirements.length === 0" class="empty-state">
        没有符合当前筛选条件的需求。
      </p>

      <ul v-else class="record-list requirement-list">
        <li v-for="item in filteredRequirements" :key="item.id" class="record requirement-card">
          <div class="record-head requirement-card-head">
            <span class="requirement-key">REQ-{{ item.id }}</span>
            <h2 class="record-title">
              <RouterLink :to="requirementDetailRoute(projectId, item.id)">
                {{ item.title }}
              </RouterLink>
            </h2>
            <span class="badge badge-neutral">REV {{ item.currentRevisionSeq }}</span>
            <span
              :class="['badge', `badge-${REQUIREMENT_STATUS_TONES[item.status]}`, 'requirement-status']"
            >
              {{ requirementPhase(item.status, activity[String(item.id)]?.activity) }}
            </span>
            <span class="requirement-facts">
              处理人
              {{ requirementHandler(item.status, activity[String(item.id)]?.activity, item.assigneeUsername, item.reviewerName) }}
              · 开发 {{ item.assigneeUsername ?? "未指派" }}
              · 评审 {{ item.reviewerName ?? "项目负责人处理" }}
              · 更新 {{ formatDateTime(item.updatedAt) }}
            </span>
            <div v-if="isLeader" class="record-actions requirement-actions">
              <RouterLink
                v-if="!isTerminal(item.status)"
                class="button button-quiet"
                :to="requirementDetailRoute(projectId, item.id)"
              >{{ item.status === "DRAFT" ? "编辑草稿" : "发布新版本" }}</RouterLink>
              <button
                v-if="canCancel(item)"
                type="button"
                class="button button-quiet"
                :disabled="rowPending === item.id"
                @click="cancelRequirement(item)"
              >作废</button>
              <button
                v-if="item.status === 'CANCELED'"
                type="button"
                class="button button-danger"
                :disabled="rowPending === item.id"
                @click="removeRequirement(item)"
              >删除</button>
              <p v-if="item.status === 'DONE'" class="field-hint">
                已完成的需求不再接受编辑、修订或状态流转。
              </p>
            </div>
          </div>
        </li>
      </ul>
      </template>

      <details v-if="isLeader" class="panel requirement-create-disclosure">
        <summary>新建需求</summary>
        <form class="requirement-form requirement-create" @submit.prevent="create">
          <div class="form-section-head">
            <div>
              <h2 class="panel-title">创建需求契约</h2>
            </div>
            <p class="field-hint">创建后先处于草稿状态；发布就绪后内容以版本保存。</p>
          </div>
          <div class="field">
            <label for="requirement-title">标题</label>
            <input id="requirement-title" v-model="title" required maxlength="200" />
          </div>
          <div class="field">
            <label for="requirement-background">背景</label>
            <textarea id="requirement-background" v-model="background" rows="3"></textarea>
          </div>
          <div class="field">
            <label for="requirement-description">描述</label>
            <textarea id="requirement-description" v-model="description" rows="4"></textarea>
          </div>
          <AcceptanceCriteriaEditor v-model="criteria" id-prefix="create" />
          <div class="form-actions">
            <button type="submit" class="button button-primary" :disabled="creating">创建需求</button>
          </div>
          <p v-if="createError" class="alert" role="alert">{{ createError }}</p>
        </form>
      </details>
    </template>
  </section>
</template>

<style scoped>
.form-section-head .panel-title {
  margin-bottom: var(--fp-space-1);
}

/* 项目与筛选合并成一条工具栏:选项一行排开,右侧给出筛选计数。 */
.requirement-toolbar {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: var(--fp-space-3) var(--fp-space-4);
  padding: var(--fp-space-4) var(--fp-space-5);
}

.requirement-toolbar .field {
  min-width: 0;
}

.toolbar-project { flex: 0 1 15rem; }
.toolbar-search { flex: 1 1 18rem; }
.toolbar-status,
.toolbar-activity { flex: 0 1 11rem; }

.toolbar-summary {
  margin: 0 0 var(--fp-space-2) auto;
  color: var(--fp-color-text-muted);
  font-size: 0.8125rem;
  white-space: nowrap;
}

.requirement-form {
  display: grid;
  gap: var(--fp-space-5);
}

.requirement-create {
  margin-top: var(--fp-space-5);
}

.requirement-create-disclosure {
  border-color: var(--fp-color-border-accent);
}

.requirement-create-disclosure > summary {
  color: var(--fp-color-accent-inverse);
  cursor: pointer;
  font-weight: 750;
}

.form-section-head {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--fp-space-4);
}

/* 一行式需求卡片:标题/徽标/事实同一行,动作右对齐;列表区定高内滚,整页不再无限拉长。 */
.requirement-card-head {
  align-items: center;
  row-gap: var(--fp-space-2);
}

.requirement-facts {
  flex: 1 1 20rem;
  min-width: 0;
  color: var(--fp-color-text-muted);
  font-size: 0.8125rem;
  overflow-wrap: anywhere;
}

.requirement-actions {
  margin-top: 0;
  margin-left: auto;
}

.requirement-list {
  max-height: 48rem;
  padding-right: var(--fp-space-2);
  overflow: auto;
}

.form-section-head .eyebrow {
  margin-bottom: var(--fp-space-2);
}

.requirement-list {
  gap: var(--fp-space-3);
}

/* 单列卡片:删掉一直没有内容的第二列,标题行因此可以铺满整行。 */
.requirement-card {
  display: block;
}

.requirement-key {
  padding: var(--fp-space-2) var(--fp-space-3);
  border: 0.0625rem solid var(--fp-color-border-accent);
  border-radius: var(--fp-radius-sm);
  background: var(--fp-color-accent-soft);
  color: var(--fp-color-accent-inverse);
  font: 800 0.6875rem/1 var(--fp-font-mono);
  letter-spacing: 0.05em;
}

.requirement-card .record-title {
  margin-right: auto;
}

@media (max-width: 64rem) {
  .toolbar-project,
  .toolbar-search,
  .toolbar-status,
  .toolbar-activity {
    flex: 1 1 12rem;
  }

  .toolbar-summary {
    margin-left: 0;
  }
}

@media (max-width: 42rem) {
  .form-section-head {
    align-items: flex-start;
    flex-direction: column;
    gap: var(--fp-space-2);
  }
}
</style>
