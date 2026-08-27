<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";

import {
  parseId,
  requirementDetailRoute,
  requirementsRoute,
  PROJECT_QUERY_KEY,
} from "../../app/routes";
import { formatDateTime } from "../../lib/datetime";
import { apiErrorMessage } from "../../lib/http";
import { hasProjectRole, listProjects, type Project } from "../project/api";
import { listReviewActivity, type ActivityView } from "../review/api";
import {
  REVIEW_ACTIVITIES,
  REVIEW_ACTIVITY_LABELS,
  REVIEW_ACTIVITY_TONES,
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
  REQUIREMENT_STATUS_LABELS,
  REQUIREMENT_STATUSES,
  REQUIREMENT_STATUS_TONES,
  STATUS_TRANSITIONS,
} from "./status";

const route = useRoute();
const router = useRouter();

const projectId = computed(() => parseId(route.query[PROJECT_QUERY_KEY]));
const projects = ref<Project[]>([]);
const requirements = ref<RequirementSummary[]>([]);
const activity = ref<Record<string, ActivityView>>({});
const loading = ref(false);
const loadError = ref<string | null>(null);
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

const selectedProject = computed(
  () => projects.value.find((project) => project.id === projectId.value) ?? null,
);
const isLeader = computed(() => hasProjectRole(selectedProject.value, "LEADER"));

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
    if (value !== null) {
      void router.push(requirementsRoute(value));
    }
  },
});

onMounted(async () => {
  try {
    projects.value = await listProjects();
  } catch (failure: unknown) {
    loadError.value = apiErrorMessage(failure);
  }
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
      <p class="eyebrow">Requirement contracts</p>
      <h1 id="requirements-title">研发需求</h1>
      <p class="lede">用不可变版本与稳定 AC 描述“应该做什么”，让后续审查拥有可追踪的判断基准。</p>
    </div>

    <div class="panel inline-form project-selector">
      <div class="selector-copy">
        <h2 class="panel-title">项目上下文</h2>
        <p class="field-hint">需求始终在一个明确的项目边界内读取和修改。</p>
      </div>
      <div class="field">
        <label for="requirement-project">当前项目</label>
        <select id="requirement-project" v-model="selection">
          <option :value="null" disabled>请选择项目</option>
          <option v-for="project in projects" :key="project.id" :value="project.id">
            {{ project.name }}
          </option>
        </select>
      </div>
    </div>

    <p v-if="projectId === null" class="empty-state">先选择一个项目，再查看它的需求。</p>

    <template v-if="projectId !== null">
      <p v-if="loading" class="muted">正在加载需求…</p>
      <p v-else-if="loadError" class="alert" role="alert">{{ loadError }}</p>
      <p v-else-if="requirements.length === 0" class="empty-state">该项目还没有需求。</p>

      <template v-else>
      <section class="panel requirement-filters" aria-label="筛选需求">
        <div class="field">
          <label for="requirement-search">搜索</label>
          <input
            id="requirement-search"
            v-model="search"
            type="search"
            placeholder="需求编号、标题或负责人"
          />
        </div>
        <div class="field">
          <label for="requirement-status-filter">需求状态</label>
          <select id="requirement-status-filter" v-model="statusFilter">
            <option value="">全部状态</option>
            <option v-for="status in REQUIREMENT_STATUSES" :key="status" :value="status">
              {{ REQUIREMENT_STATUS_LABELS[status] }}
            </option>
          </select>
        </div>
        <div class="field">
          <label for="requirement-activity-filter">评审活动</label>
          <select id="requirement-activity-filter" v-model="activityFilter">
            <option value="">全部活动</option>
            <option v-for="state in REVIEW_ACTIVITIES" :key="state" :value="state">
              {{ REVIEW_ACTIVITY_LABELS[state] }}
            </option>
          </select>
        </div>
      </section>

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
          </div>

          <dl class="meta-list requirement-meta">
            <div>
              <dt>需求状态</dt>
              <dd class="requirement-status">
                <span :class="['badge', `badge-${REQUIREMENT_STATUS_TONES[item.status]}`]">
                  {{ REQUIREMENT_STATUS_LABELS[item.status] }}
                </span>
              </dd>
            </div>
            <div>
              <dt>负责人</dt>
              <dd>{{ item.assigneeUsername ?? "未指派" }}</dd>
            </div>
            <div>
              <dt>评审活动</dt>
              <dd class="review-activity">
                <span
                  v-if="activity[String(item.id)]"
                  :class="[
                    'badge',
                    `badge-${REVIEW_ACTIVITY_TONES[activity[String(item.id)].activity]}`,
                  ]"
                >
                  {{ REVIEW_ACTIVITY_LABELS[activity[String(item.id)].activity] }}
                </span>
                <span v-else class="badge badge-neutral">未返回</span>
              </dd>
            </div>
            <div>
              <dt>更新时间</dt>
              <dd>{{ formatDateTime(item.updatedAt) }}</dd>
            </div>
          </dl>

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
        </li>
      </ul>
      </template>

      <details v-if="isLeader" class="panel requirement-create-disclosure">
        <summary>新建需求</summary>
        <form class="requirement-form requirement-create" @submit.prevent="create">
          <div class="form-section-head">
            <div>
              <p class="eyebrow">New contract</p>
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
.project-selector {
  display: grid;
  grid-template-columns: minmax(14rem, 1fr) minmax(14rem, 1.2fr);
}

.selector-copy .panel-title,
.form-section-head .panel-title {
  margin-bottom: var(--fp-space-1);
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
  gap: var(--fp-space-6);
}

.form-section-head .eyebrow {
  margin-bottom: var(--fp-space-2);
}

.requirement-list {
  gap: var(--fp-space-4);
}

.requirement-filters {
  display: grid;
  align-items: end;
  grid-template-columns: minmax(16rem, 1.4fr) repeat(2, minmax(11rem, 0.7fr));
  gap: var(--fp-space-4);
}

.requirement-card {
  display: grid;
  gap: var(--fp-space-5);
  grid-template-columns: minmax(0, 1fr) minmax(16rem, 0.48fr);
}

.requirement-card-head {
  grid-column: 1;
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

.requirement-meta {
  grid-column: 2;
  grid-row: 1;
  padding-left: var(--fp-space-5);
  border-left: 0.0625rem solid var(--fp-color-border);
}

@media (max-width: 64rem) {
  .project-selector,
  .requirement-filters,
  .requirement-card {
    grid-template-columns: 1fr;
  }

  .requirement-meta {
    grid-column: 1;
    grid-row: auto;
    padding-top: var(--fp-space-5);
    padding-left: 0;
    border-top: 0.0625rem solid var(--fp-color-border);
    border-left: 0;
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
