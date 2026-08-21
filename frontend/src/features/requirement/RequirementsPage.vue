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
import { listProjects, type Project } from "../project/api";
import AcceptanceCriteriaEditor from "./AcceptanceCriteriaEditor.vue";
import {
  createRequirement,
  listRequirements,
  type AcceptanceCriterionDraft,
  type RequirementSummary,
} from "./api";
import {
  REQUIREMENT_STATUS_LABELS,
  REQUIREMENT_STATUS_TONES,
  REVIEW_ACTIVITY_LABELS,
} from "./status";

const route = useRoute();
const router = useRouter();

const projectId = computed(() => parseId(route.query[PROJECT_QUERY_KEY]));
const projects = ref<Project[]>([]);
const requirements = ref<RequirementSummary[]>([]);
const loading = ref(false);
const loadError = ref<string | null>(null);

const title = ref("");
const background = ref("");
const description = ref("");
const criteria = ref<AcceptanceCriterionDraft[]>([{ text: "" }]);
const creating = ref(false);
const createError = ref<string | null>(null);

const selectedProject = computed(
  () => projects.value.find((project) => project.id === projectId.value) ?? null,
);
const isLeader = computed(() => selectedProject.value?.myRole === "LEADER");

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
    requirements.value = [];
    if (id === null) {
      return;
    }
    loading.value = true;
    loadError.value = null;
    try {
      requirements.value = await listRequirements(id);
    } catch (failure: unknown) {
      loadError.value = apiErrorMessage(failure);
    } finally {
      loading.value = false;
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
</script>

<template>
  <section aria-labelledby="requirements-title">
    <div class="page-head">
      <p class="eyebrow">Requirement</p>
      <h1 id="requirements-title">研发需求</h1>
    </div>

    <div class="panel inline-form">
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
      <form v-if="isLeader" class="panel requirement-form" @submit.prevent="create">
        <h2 class="panel-title">新建需求</h2>
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
          <button type="submit" class="button button-primary" :disabled="creating">
            创建需求
          </button>
        </div>
        <p v-if="createError" class="alert" role="alert">{{ createError }}</p>
      </form>

      <p v-if="loading" class="muted">正在加载需求…</p>
      <p v-else-if="loadError" class="alert" role="alert">{{ loadError }}</p>
      <p v-else-if="requirements.length === 0" class="empty-state">该项目还没有需求。</p>

      <ul v-else class="record-list">
        <li v-for="item in requirements" :key="item.id" class="record">
          <div class="record-head">
            <h2 class="record-title">
              <RouterLink :to="requirementDetailRoute(projectId, item.id)">
                {{ item.title }}
              </RouterLink>
            </h2>
            <span class="badge badge-neutral">v{{ item.currentRevisionSeq }}</span>
          </div>

          <dl class="meta-list">
            <div>
              <dt>需求状态</dt>
              <dd class="requirement-status">
                <span :class="['badge', `badge-${REQUIREMENT_STATUS_TONES[item.status]}`]">
                  {{ REQUIREMENT_STATUS_LABELS[item.status] }}
                </span>
              </dd>
            </div>
            <div>
              <dt>评审活动</dt>
              <dd class="review-activity">
                <span class="badge badge-neutral">
                  {{ REVIEW_ACTIVITY_LABELS[item.reviewActivity] }}
                </span>
                <code>{{ item.reviewActivity }}</code>
              </dd>
            </div>
            <div>
              <dt>负责人</dt>
              <dd>{{ item.assigneeUsername ?? "未指派" }}</dd>
            </div>
            <div>
              <dt>更新时间</dt>
              <dd>{{ formatDateTime(item.updatedAt) }}</dd>
            </div>
          </dl>
        </li>
      </ul>
    </template>
  </section>
</template>

<style scoped>
.requirement-form {
  display: grid;
  gap: var(--fp-space-4);
}
</style>
