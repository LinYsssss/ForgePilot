<script setup lang="ts">
import { onMounted, ref } from "vue";
import { RouterLink } from "vue-router";

import {
  projectMembersRoute,
  projectSettingsRoute,
  requirementsRoute,
  reviewsRoute,
} from "../../app/routes";
import { formatDateTime } from "../../lib/datetime";
import { apiErrorMessage } from "../../lib/http";
import {
  createProject,
  listProjects,
  PROJECT_ROLE_LABELS,
  PROJECT_STATUS_LABELS,
  type Project,
} from "./api";

const projects = ref<Project[]>([]);
const loading = ref(true);
const loadError = ref<string | null>(null);

const newName = ref("");
const creating = ref(false);
const createError = ref<string | null>(null);

onMounted(async () => {
  try {
    projects.value = await listProjects();
  } catch (failure: unknown) {
    loadError.value = apiErrorMessage(failure);
  } finally {
    loading.value = false;
  }
});

async function create(): Promise<void> {
  creating.value = true;
  createError.value = null;
  try {
    projects.value = [...projects.value, await createProject(newName.value)];
    newName.value = "";
  } catch (failure: unknown) {
    createError.value = apiErrorMessage(failure);
  } finally {
    creating.value = false;
  }
}
</script>

<template>
  <section aria-labelledby="projects-title">
    <div class="page-head">
      <p class="eyebrow">Project</p>
      <h1 id="projects-title">项目</h1>
    </div>

    <form class="panel inline-form" @submit.prevent="create">
      <div class="field">
        <label for="new-project-name">新建项目名称</label>
        <input id="new-project-name" v-model="newName" required maxlength="120" />
      </div>
      <button type="submit" class="button button-primary" :disabled="creating">创建项目</button>
      <p v-if="createError" class="alert" role="alert">{{ createError }}</p>
    </form>

    <p v-if="loading" class="muted">正在加载项目…</p>
    <p v-else-if="loadError" class="alert" role="alert">{{ loadError }}</p>
    <p v-else-if="projects.length === 0" class="empty-state">还没有项目，先创建一个。</p>

    <ul v-else class="record-list">
      <li v-for="project in projects" :key="project.id" class="record">
        <div class="record-head">
          <h2 class="record-title">{{ project.name }}</h2>
          <span class="badge badge-neutral">{{ PROJECT_STATUS_LABELS[project.status] }}</span>
          <span class="badge badge-info">我的角色：{{ PROJECT_ROLE_LABELS[project.myRole] }}</span>
        </div>
        <p class="muted">创建于 {{ formatDateTime(project.createdAt) }}</p>
        <div class="record-actions">
          <RouterLink class="button button-quiet" :to="projectMembersRoute(project.id)">
            成员管理
          </RouterLink>
          <RouterLink class="button button-quiet" :to="projectSettingsRoute(project.id)">
            项目设置
          </RouterLink>
          <RouterLink class="button button-quiet" :to="requirementsRoute(project.id)">
            研发需求
          </RouterLink>
          <RouterLink class="button button-quiet" :to="reviewsRoute(project.id)">
            代码审查
          </RouterLink>
        </div>
      </li>
    </ul>
  </section>
</template>
