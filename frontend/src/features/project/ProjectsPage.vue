<script setup lang="ts">
import { onMounted, ref } from "vue";
import { RouterLink } from "vue-router";

import {
  projectMembersRoute,
  knowledgeRoute,
  repositoriesRoute,
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
  <section class="projects-page" aria-labelledby="projects-title">
    <div class="page-head">
      <p class="eyebrow">Project workspace</p>
      <h1 id="projects-title">项目</h1>
      <p class="lede">管理团队边界、需求上下文与代码审查入口。每个项目只连接一个活动仓库。</p>
    </div>

    <form class="panel inline-form project-create" @submit.prevent="create">
      <div class="project-create-copy">
        <h2 class="panel-title">创建研发空间</h2>
        <p class="field-hint">创建者会在同一事务中成为项目负责人。</p>
      </div>
      <div class="field">
        <label for="new-project-name">新建项目名称</label>
        <input
          id="new-project-name"
          v-model="newName"
          placeholder="例如：ForgePilot"
          required
          maxlength="120"
        />
      </div>
      <button type="submit" class="button button-primary" :disabled="creating">创建项目</button>
      <p v-if="createError" class="alert" role="alert">{{ createError }}</p>
    </form>

    <p v-if="loading" class="muted">正在加载项目…</p>
    <p v-else-if="loadError" class="alert" role="alert">{{ loadError }}</p>
    <p v-else-if="projects.length === 0" class="empty-state">还没有项目，先创建一个。</p>

    <ul v-else class="record-list project-grid">
      <li v-for="project in projects" :key="project.id" class="record project-card">
        <div class="record-head">
          <span class="project-monogram" aria-hidden="true">{{ project.name.slice(0, 1).toUpperCase() }}</span>
          <div class="project-identity">
            <p class="project-id">PROJECT · {{ project.id }}</p>
            <h2 class="record-title">{{ project.name }}</h2>
          </div>
          <span class="badge badge-neutral">{{ PROJECT_STATUS_LABELS[project.status] }}</span>
        </div>
        <dl class="meta-list project-meta">
          <div>
            <dt>我的角色</dt>
            <dd class="role-list">
              <span v-for="role in project.myRoles" :key="role" class="badge badge-info">
                {{ PROJECT_ROLE_LABELS[role] }}
              </span>
            </dd>
          </div>
          <div>
            <dt>创建时间</dt>
            <dd>{{ formatDateTime(project.createdAt) }}</dd>
          </div>
        </dl>
        <div class="record-actions">
          <RouterLink class="button button-quiet" :to="projectMembersRoute(project.id)">
            成员管理
          </RouterLink>
          <RouterLink class="button button-quiet" :to="knowledgeRoute(project.id)">
            项目知识
          </RouterLink>
          <RouterLink class="button button-quiet" :to="repositoriesRoute(project.id)">
            仓库接入
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

<style scoped>
.project-create {
  display: grid;
  align-items: end;
  grid-template-columns: minmax(14rem, 1fr) minmax(14rem, 1.4fr) auto;
}

.project-create-copy .panel-title {
  margin-bottom: var(--fp-space-1);
}

.project-create .alert {
  grid-column: 1 / -1;
}

.project-grid {
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 25rem), 1fr));
}

.project-card {
  display: flex;
  min-height: 18rem;
  flex-direction: column;
}

.project-monogram {
  display: grid;
  flex: 0 0 auto;
  width: 2.75rem;
  height: 2.75rem;
  place-items: center;
  border: 0.0625rem solid var(--fp-color-border-accent);
  border-radius: var(--fp-radius-md);
  background: var(--fp-color-accent-soft);
  color: var(--fp-color-accent-inverse);
  font: 800 1rem/1 var(--fp-font-mono);
}

.project-identity {
  min-width: 0;
  margin-right: auto;
}

.project-id {
  margin: 0 0 var(--fp-space-1);
  color: var(--fp-color-text-subtle);
  font: 700 0.625rem/1.2 var(--fp-font-mono);
  letter-spacing: 0.08em;
}

.project-meta {
  flex: 1;
  align-content: start;
}

.project-card .record-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

@media (max-width: 64rem) {
  .project-create {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 42rem) {
  .project-card .record-actions {
    grid-template-columns: 1fr;
  }
}
</style>
