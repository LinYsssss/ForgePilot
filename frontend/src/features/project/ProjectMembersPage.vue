<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { RouterLink, useRoute } from "vue-router";

import { parseId, requirementsRoute } from "../../app/routes";
import { formatDateTime } from "../../lib/datetime";
import { apiErrorMessage } from "../../lib/http";
import {
  addMember,
  getProject,
  listMembers,
  updateMember,
  PROJECT_ROLES,
  PROJECT_ROLE_LABELS,
  type Member,
  type MemberPatch,
  type Project,
  type ProjectRole,
} from "./api";

interface MemberRow {
  member: Member;
  role: ProjectRole;
  scmExternalUserId: string;
  scmUsername: string;
  error: string | null;
  pending: boolean;
}

const route = useRoute();
const projectId = computed(() => parseId(route.params.id));

const project = ref<Project | null>(null);
const rows = ref<MemberRow[]>([]);
const loading = ref(true);
const loadError = ref<string | null>(null);

const newUsername = ref("");
const newRole = ref<ProjectRole>("DEVELOPER");
const adding = ref(false);
const addError = ref<string | null>(null);

const isLeader = computed(() => project.value?.myRole === "LEADER");

function toRow(member: Member): MemberRow {
  return {
    member,
    role: member.role,
    scmExternalUserId: member.scmExternalUserId ?? "",
    scmUsername: member.scmUsername ?? "",
    error: null,
    pending: false,
  };
}

onMounted(async () => {
  const id = projectId.value;
  if (id === null) {
    loading.value = false;
    return;
  }
  try {
    const [loadedProject, members] = await Promise.all([getProject(id), listMembers(id)]);
    project.value = loadedProject;
    rows.value = members.map(toRow);
  } catch (failure: unknown) {
    loadError.value = apiErrorMessage(failure);
  } finally {
    loading.value = false;
  }
});

async function add(): Promise<void> {
  const id = projectId.value;
  if (id === null) {
    return;
  }
  adding.value = true;
  addError.value = null;
  try {
    rows.value = [...rows.value, toRow(await addMember(id, newUsername.value, newRole.value))];
    newUsername.value = "";
  } catch (failure: unknown) {
    addError.value = apiErrorMessage(failure);
  } finally {
    adding.value = false;
  }
}

async function patch(row: MemberRow, changes: MemberPatch): Promise<void> {
  const id = projectId.value;
  if (id === null) {
    return;
  }
  row.pending = true;
  row.error = null;
  try {
    await updateMember(id, row.member.userId, changes);
    // A LEADER transfer demotes the previous leader, so reload the whole list.
    const [reloadedProject, members] = await Promise.all([getProject(id), listMembers(id)]);
    project.value = reloadedProject;
    rows.value = members.map(toRow);
  } catch (failure: unknown) {
    row.error = apiErrorMessage(failure);
  } finally {
    row.pending = false;
  }
}

function saveRole(row: MemberRow): Promise<void> {
  return patch(row, { role: row.role });
}

function saveScmIdentity(row: MemberRow): Promise<void> {
  return patch(row, {
    scmExternalUserId: row.scmExternalUserId,
    scmUsername: row.scmUsername,
  });
}
</script>

<template>
  <section aria-labelledby="members-title">
    <div class="page-head">
      <p class="eyebrow">Project · members</p>
      <h1 id="members-title">{{ project ? project.name : "项目成员" }}</h1>
      <p v-if="project" class="muted">
        我的角色：{{ PROJECT_ROLE_LABELS[project.myRole] }}
      </p>
    </div>

    <p v-if="projectId === null" class="alert" role="alert">路由缺少有效的项目 id。</p>
    <p v-else-if="loading" class="muted">正在加载成员…</p>
    <p v-else-if="loadError" class="alert" role="alert">{{ loadError }}</p>

    <template v-else>
      <form v-if="isLeader" class="panel inline-form" @submit.prevent="add">
        <div class="field">
          <label for="member-username">用户名</label>
          <input id="member-username" v-model="newUsername" required maxlength="64" />
        </div>
        <div class="field">
          <label for="member-role">角色</label>
          <select id="member-role" v-model="newRole">
            <option v-for="role in PROJECT_ROLES" :key="role" :value="role">
              {{ PROJECT_ROLE_LABELS[role] }}
            </option>
          </select>
        </div>
        <button type="submit" class="button button-primary" :disabled="adding">添加成员</button>
        <p v-if="addError" class="alert" role="alert">{{ addError }}</p>
      </form>
      <p v-else class="empty-state">只有项目负责人可以管理成员。</p>

      <ul class="record-list">
        <li v-for="row in rows" :key="row.member.userId" class="record">
          <div class="record-head">
            <h2 class="record-title">{{ row.member.username }}</h2>
            <span class="badge badge-info">{{ PROJECT_ROLE_LABELS[row.member.role] }}</span>
          </div>

          <dl class="meta-list">
            <div>
              <dt>SCM 外部 id</dt>
              <dd>{{ row.member.scmExternalUserId ?? "未配置" }}</dd>
            </div>
            <div>
              <dt>SCM 用户名</dt>
              <dd>{{ row.member.scmUsername ?? "未配置" }}</dd>
            </div>
            <div>
              <dt>身份核对时间</dt>
              <dd>
                {{
                  row.member.scmIdentityVerifiedAt
                    ? formatDateTime(row.member.scmIdentityVerifiedAt)
                    : "未核对"
                }}
              </dd>
            </div>
          </dl>

          <template v-if="isLeader">
            <form class="inline-form" @submit.prevent="saveRole(row)">
              <div class="field">
                <label :for="`role-${row.member.userId}`">角色</label>
                <select :id="`role-${row.member.userId}`" v-model="row.role">
                  <option v-for="role in PROJECT_ROLES" :key="role" :value="role">
                    {{ PROJECT_ROLE_LABELS[role] }}
                  </option>
                </select>
              </div>
              <button type="submit" class="button button-quiet" :disabled="row.pending">
                保存角色
              </button>
              <p class="field-hint">改为「负责人」即转移项目负责人，原负责人同时降级。</p>
            </form>

            <form class="inline-form" @submit.prevent="saveScmIdentity(row)">
              <div class="field">
                <label :for="`scm-id-${row.member.userId}`">SCM 外部 id</label>
                <input
                  :id="`scm-id-${row.member.userId}`"
                  v-model="row.scmExternalUserId"
                  required
                  maxlength="128"
                />
              </div>
              <div class="field">
                <label :for="`scm-name-${row.member.userId}`">SCM 用户名</label>
                <input
                  :id="`scm-name-${row.member.userId}`"
                  v-model="row.scmUsername"
                  required
                  maxlength="128"
                />
              </div>
              <button type="submit" class="button button-quiet" :disabled="row.pending">
                保存 SCM 身份
              </button>
            </form>
          </template>

          <p v-if="row.error" class="alert" role="alert">{{ row.error }}</p>
        </li>
      </ul>

      <div v-if="projectId !== null" class="record-actions">
        <RouterLink class="button button-quiet" :to="requirementsRoute(projectId)">
          查看该项目需求
        </RouterLink>
      </div>
    </template>
  </section>
</template>
