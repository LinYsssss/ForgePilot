<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
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
  archiveProject,
  createProject,
  hasProjectRole,
  listProjects,
  PROJECT_ROLE_LABELS,
  PROJECT_STATUS_LABELS,
  unarchiveProject,
  type Project,
} from "./api";

const projects = ref<Project[]>([]);
const loading = ref(true);
const loadError = ref<string | null>(null);

const newName = ref("");
const creating = ref(false);
const createError = ref<string | null>(null);

const activeProjects = computed(() => projects.value.filter((it) => it.status !== "ARCHIVED"));
const archivedProjects = computed(() => projects.value.filter((it) => it.status === "ARCHIVED"));

/** 正在确认归档的那个项目；同一时刻只可能有一个。 */
const archiveTarget = ref<Project | null>(null);
const archiveInput = ref("");
const archiving = ref(false);
const archiveError = ref<string | null>(null);
const copied = ref(false);

/**
 * 二次确认的那道闸：必须把项目名一字不差地重新输入一遍。
 * 不做 trim、不忽略大小写——放宽任何一条，这道闸就挡不住误操作了。
 */
const archiveConfirmed = computed(
  () => archiveTarget.value !== null && archiveInput.value === archiveTarget.value.name,
);

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

function askArchive(project: Project): void {
  archiveTarget.value = project;
  archiveInput.value = "";
  archiveError.value = null;
  copied.value = false;
}

function cancelArchive(): void {
  archiveTarget.value = null;
  archiveInput.value = "";
  archiveError.value = null;
  copied.value = false;
}

/**
 * 剪贴板 API 在非安全上下文里会直接抛，测试环境（jsdom）根本没有它。
 * 失败不报错也不拦路：项目名就在旁边的只读输入框里，随时可以手动选中复制。
 */
async function copyName(): Promise<void> {
  const name = archiveTarget.value?.name;
  if (name === undefined) return;
  try {
    await navigator.clipboard.writeText(name);
    copied.value = true;
  } catch {
    copied.value = false;
  }
}

async function confirmArchive(): Promise<void> {
  const target = archiveTarget.value;
  if (target === null || !archiveConfirmed.value) return;
  archiving.value = true;
  archiveError.value = null;
  try {
    await archiveProject(target.id);
    projects.value = projects.value.map((it) =>
      it.id === target.id ? { ...it, status: "ARCHIVED" as const } : it,
    );
    cancelArchive();
  } catch (failure: unknown) {
    archiveError.value = apiErrorMessage(failure);
  } finally {
    archiving.value = false;
  }
}

async function restore(project: Project): Promise<void> {
  archiving.value = true;
  archiveError.value = null;
  try {
    await unarchiveProject(project.id);
    projects.value = projects.value.map((it) =>
      it.id === project.id ? { ...it, status: "ACTIVE" as const } : it,
    );
  } catch (failure: unknown) {
    archiveError.value = apiErrorMessage(failure);
  } finally {
    archiving.value = false;
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

    <template v-else>
      <p v-if="activeProjects.length === 0" class="empty-state">
        全部项目都已归档。可在下方展开恢复。
      </p>
      <ul v-else class="record-list project-grid">
        <li v-for="project in activeProjects" :key="project.id" class="record project-card">
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
            <button
              v-if="hasProjectRole(project, 'LEADER')"
              type="button"
              class="button button-danger"
              @click="askArchive(project)"
            >归档项目</button>
          </div>

          <!-- 二次确认就地展开，不做 modal：jsdom 不实现 <dialog>，
               而手写 focus trap 只为一个确认框并不划算。 -->
          <form
            v-if="archiveTarget?.id === project.id"
            class="archive-confirm"
            @submit.prevent="confirmArchive"
          >
            <p class="field-hint">
              归档后该项目从上方列表收起，成员、需求、知识、审查与全部审计
              <strong>一行不动</strong>，随时可以恢复。请重新输入项目名以确认。
            </p>
            <div class="archive-name">
              <input
                :value="project.name"
                class="archive-name-source"
                readonly
                aria-label="项目名（可复制）"
                @focus="($event.target as HTMLInputElement).select()"
              />
              <button type="button" class="button button-quiet" @click="copyName">
                {{ copied ? "已复制" : "复制" }}
              </button>
            </div>
            <div class="field">
              <label :for="`archive-input-${project.id}`">重新输入项目名</label>
              <input
                :id="`archive-input-${project.id}`"
                v-model="archiveInput"
                autocomplete="off"
                :placeholder="project.name"
              />
            </div>
            <p v-if="archiveError" class="alert" role="alert">{{ archiveError }}</p>
            <div class="form-actions">
              <button
                type="submit"
                class="button button-danger"
                :disabled="!archiveConfirmed || archiving"
              >{{ archiving ? "正在归档…" : "确认归档" }}</button>
              <button type="button" class="button button-quiet" @click="cancelArchive">取消</button>
            </div>
          </form>
        </li>
      </ul>

      <details v-if="archivedProjects.length > 0" class="panel archived-disclosure">
        <summary>已归档项目（{{ archivedProjects.length }}）</summary>
        <ul class="archived-list">
          <li v-for="project in archivedProjects" :key="project.id">
            <div class="archived-identity">
              <strong>{{ project.name }}</strong>
              <span class="muted">PROJECT · {{ project.id }} · 创建于 {{ formatDateTime(project.createdAt) }}</span>
            </div>
            <button
              v-if="hasProjectRole(project, 'LEADER')"
              type="button"
              class="button button-quiet"
              :disabled="archiving"
              @click="restore(project)"
            >恢复</button>
          </li>
        </ul>
      </details>
    </template>
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

/* 动作数为奇数时（非 LEADER 看不到归档按钮）让最后一个占满整行，
   否则两列网格的末行会留半个空格。 */
.project-card .record-actions > :last-child:nth-child(odd) {
  grid-column: 1 / -1;
}

.archive-confirm {
  display: grid;
  gap: var(--fp-space-3);
  padding-top: var(--fp-space-4);
  border-top: 0.0625rem solid var(--fp-color-border);
  margin-top: var(--fp-space-4);
}

.archive-name {
  display: flex;
  gap: var(--fp-space-3);
  align-items: center;
}

.archive-name-source {
  min-width: 0;
  flex: 1;
  font-family: var(--fp-font-mono);
}

.archived-disclosure > summary {
  cursor: pointer;
}

.archived-list {
  display: grid;
  gap: var(--fp-space-3);
  padding: 0;
  margin: var(--fp-space-4) 0 0;
  list-style: none;
}

.archived-list > li {
  display: flex;
  gap: var(--fp-space-4);
  align-items: center;
  justify-content: space-between;
  padding: var(--fp-space-3) 0;
  border-bottom: 0.0625rem solid var(--fp-color-border);
}

.archived-list > li:last-child {
  border-bottom: 0;
}

.archived-identity {
  display: grid;
  min-width: 0;
  gap: var(--fp-space-1);
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
