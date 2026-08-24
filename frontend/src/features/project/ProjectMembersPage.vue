<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { RouterLink, useRoute } from "vue-router";

import { parseId, requirementsRoute } from "../../app/routes";
import { formatDateTime } from "../../lib/datetime";
import { apiErrorMessage } from "../../lib/http";
import { useSession } from "../auth/session";
import {
  bindScmIdentity,
  decideScmBinding,
  listBindingOptions,
  listScmBindings,
  type ScmBinding,
  type ScmIdentity,
} from "../scm/api";
import {
  addMembers,
  getProject,
  hasProjectRole,
  listMembers,
  PROJECT_ROLE_LABELS,
  searchMemberCandidates,
  transferLeader,
  updateMemberRoles,
  type Member,
  type MemberCandidate,
  type Project,
  type ProjectRole,
} from "./api";

interface SelectedCandidate {
  candidate: MemberCandidate;
  roles: ProjectRole[];
}

const route = useRoute();
const { account } = useSession();
const projectId = computed(() => parseId(route.params.id));
const project = ref<Project | null>(null);
const members = ref<Member[]>([]);
const candidates = ref<MemberCandidate[]>([]);
const selected = ref<SelectedCandidate[]>([]);
const bindings = ref<ScmBinding[]>([]);
const bindingOptions = ref<ScmIdentity[]>([]);
const roleDrafts = ref<Record<string, ProjectRole[]>>({});
const bindingIdentityId = ref<number | null>(null);
const bindingToken = ref("");
const query = ref("");
const commonRoles = ref<ProjectRole[]>(["DEVELOPER"]);
const loading = ref(true);
const pending = ref(false);
const error = ref<string | null>(null);

const isLeader = computed(() => hasProjectRole(project.value, "LEADER"));
const assignableRoles: ProjectRole[] = ["DEVELOPER", "REVIEWER"];

function resetDrafts(): void {
  roleDrafts.value = Object.fromEntries(
    members.value.map((member) => [String(member.userId), [...member.roles]]),
  );
}

async function load(): Promise<void> {
  const id = projectId.value;
  if (id === null) {
    loading.value = false;
    error.value = "路由缺少有效的项目 ID。";
    return;
  }
  loading.value = true;
  error.value = null;
  try {
    const [loadedProject, loadedMembers, loadedBindings, options] = await Promise.all([
      getProject(id),
      listMembers(id),
      listScmBindings(id),
      listBindingOptions(id),
    ]);
    project.value = loadedProject;
    members.value = loadedMembers;
    bindings.value = loadedBindings;
    bindingOptions.value = options;
    bindingIdentityId.value = options[0]?.id ?? null;
    resetDrafts();
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  } finally {
    loading.value = false;
  }
}

async function search(): Promise<void> {
  const id = projectId.value;
  if (id === null || query.value.trim().length < 2) return;
  pending.value = true;
  error.value = null;
  try {
    candidates.value = await searchMemberCandidates(id, query.value.trim());
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  } finally {
    pending.value = false;
  }
}

function isSelected(userId: number): boolean {
  return selected.value.some((row) => row.candidate.userId === userId);
}

function toggleCandidate(candidate: MemberCandidate): void {
  if (candidate.alreadyMember || !candidate.enabled) return;
  const existing = selected.value.findIndex((row) => row.candidate.userId === candidate.userId);
  if (existing >= 0) {
    selected.value.splice(existing, 1);
  } else {
    selected.value.push({ candidate, roles: [...commonRoles.value] });
  }
}

function applyCommonRoles(): void {
  selected.value.forEach((row) => { row.roles = [...commonRoles.value]; });
}

function toggleRole(roles: ProjectRole[], role: ProjectRole): void {
  const index = roles.indexOf(role);
  if (index >= 0) roles.splice(index, 1);
  else roles.push(role);
}

async function addSelected(): Promise<void> {
  const id = projectId.value;
  if (id === null || selected.value.length === 0) return;
  pending.value = true;
  error.value = null;
  try {
    await addMembers(id, selected.value.map((row) => ({
      userId: row.candidate.userId,
      roles: row.roles,
    })));
    selected.value = [];
    candidates.value = [];
    await load();
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  } finally {
    pending.value = false;
  }
}

async function saveRoles(member: Member): Promise<void> {
  const id = projectId.value;
  const roles = roleDrafts.value[String(member.userId)] ?? [];
  if (id === null) return;
  try {
    await updateMemberRoles(id, member.userId, roles);
    await load();
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  }
}

async function makeLeader(member: Member): Promise<void> {
  const id = projectId.value;
  if (id === null || !window.confirm(`确认把项目负责人转移给 ${member.displayName}？`)) return;
  try {
    await transferLeader(id, member.userId);
    await load();
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  }
}

function currentBinding(userId: number): ScmBinding | null {
  return [...bindings.value].reverse().find(
    (binding) => binding.userId === userId &&
      ["ACTIVE", "PENDING_APPROVAL", "LEGACY_UNCONFIRMED"].includes(binding.status),
  ) ?? null;
}

const memberRows = computed(() => members.value.map((member) => ({
  member,
  binding: currentBinding(member.userId),
})));

async function bindMine(): Promise<void> {
  const id = projectId.value;
  if (id === null || bindingIdentityId.value === null) return;
  pending.value = true;
  error.value = null;
  try {
    await bindScmIdentity(id, bindingIdentityId.value, bindingToken.value);
    bindingToken.value = "";
    await load();
  } catch (failure: unknown) {
    bindingToken.value = "";
    error.value = apiErrorMessage(failure);
  } finally {
    pending.value = false;
  }
}

async function decide(binding: ScmBinding, decision: "approve" | "reject"): Promise<void> {
  const id = projectId.value;
  if (id === null) return;
  await decideScmBinding(id, binding.id, decision);
  await load();
}

onMounted(load);
</script>

<template>
  <section aria-labelledby="members-title">
    <div class="page-head">
      <p class="eyebrow">Project · people and identities</p>
      <h1 id="members-title">{{ project?.name ?? "项目成员" }}</h1>
      <p v-if="project" class="lede">
        我的角色：{{ project.myRoles.map((role) => PROJECT_ROLE_LABELS[role]).join("、") }}
      </p>
    </div>

    <p v-if="loading" class="muted">正在加载成员…</p>
    <p v-if="error" class="alert" role="alert">{{ error }}</p>

    <section v-if="!loading && isLeader" class="panel batch-panel" aria-labelledby="batch-title">
      <h2 id="batch-title" class="panel-title">搜索并批量添加成员</h2>
      <form class="inline-form" @submit.prevent="search">
        <div class="field">
          <label for="candidate-query">显示名、用户名或平台 ID</label>
          <input id="candidate-query" v-model="query" required minlength="2" maxlength="120" />
        </div>
        <button class="button button-quiet" :disabled="pending">搜索</button>
      </form>
      <ul v-if="candidates.length" class="record-list candidate-list">
        <li v-for="candidate in candidates" :key="candidate.userId" class="record">
          <label class="candidate-choice">
            <input
              type="checkbox"
              :checked="isSelected(candidate.userId)"
              :disabled="candidate.alreadyMember || !candidate.enabled"
              @change="toggleCandidate(candidate)"
            />
            <span><strong>{{ candidate.displayName }}</strong> · @{{ candidate.username }} · ID {{ candidate.userId }}</span>
          </label>
          <span v-if="candidate.alreadyMember" class="badge badge-neutral">已是成员</span>
        </li>
      </ul>

      <template v-if="selected.length">
        <div class="role-picker">
          <strong>共同角色</strong>
          <label v-for="role in assignableRoles" :key="role">
            <input
              type="checkbox"
              :checked="commonRoles.includes(role)"
              @change="toggleRole(commonRoles, role); applyCommonRoles()"
            /> {{ PROJECT_ROLE_LABELS[role] }}
          </label>
        </div>
        <ul class="record-list">
          <li v-for="row in selected" :key="row.candidate.userId" class="record preview-row">
            <span>{{ row.candidate.displayName }} · @{{ row.candidate.username }} · ID {{ row.candidate.userId }}</span>
            <label v-for="role in assignableRoles" :key="role">
              <input type="checkbox" :checked="row.roles.includes(role)" @change="toggleRole(row.roles, role)" />
              {{ PROJECT_ROLE_LABELS[role] }}
            </label>
          </li>
        </ul>
        <button class="button button-primary" :disabled="pending" @click="addSelected">
          一次添加 {{ selected.length }} 人
        </button>
      </template>
    </section>

    <ul v-if="!loading" class="record-list member-grid">
      <li v-for="row in memberRows" :key="row.member.userId" class="record member-card">
        <div class="record-head">
          <div>
            <h2 class="record-title">{{ row.member.displayName }}</h2>
            <p class="field-hint">@{{ row.member.username }} · 平台 ID {{ row.member.userId }}</p>
          </div>
          <span v-for="role in row.member.roles" :key="role" class="badge badge-info">
            {{ PROJECT_ROLE_LABELS[role] }}
          </span>
        </div>

        <template v-if="row.binding">
          <dl class="meta-list">
            <div><dt>SCM 状态</dt><dd>{{ row.binding.status }}</dd></div>
            <div><dt>所选身份</dt><dd>{{ row.binding.label }} · {{ row.binding.usageType }}</dd></div>
            <div><dt>远程账号</dt><dd>{{ row.binding.externalUsername }} · ID {{ row.binding.externalUserId }}</dd></div>
            <div><dt>仓库权限</dt><dd>{{ row.binding.accessLevel ?? "待验证" }}</dd></div>
            <div><dt>核验时间</dt><dd>{{ row.binding.accessCheckedAt ? formatDateTime(row.binding.accessCheckedAt) : "待确认" }}</dd></div>
          </dl>
          <div v-if="isLeader && row.binding.status === 'PENDING_APPROVAL'" class="record-actions">
            <button class="button button-primary" @click="decide(row.binding, 'approve')">批准</button>
            <button class="button button-quiet" @click="decide(row.binding, 'reject')">拒绝</button>
          </div>
        </template>
        <p v-else class="field-hint">
          {{ row.member.roles.includes("DEVELOPER") ? "等待成员绑定 SCM 身份" : "当前角色无需绑定 SCM 身份" }}
        </p>

        <form v-if="isLeader" class="member-actions" @submit.prevent="saveRoles(row.member)">
          <label v-for="role in assignableRoles" :key="role">
            <input
              type="checkbox"
              :checked="roleDrafts[String(row.member.userId)]?.includes(role)"
              @change="toggleRole(roleDrafts[String(row.member.userId)], role)"
            /> {{ PROJECT_ROLE_LABELS[role] }}
          </label>
          <button class="button button-quiet">保存角色</button>
          <button
            v-if="!row.member.roles.includes('LEADER')"
            type="button"
            class="button button-quiet"
            @click="makeLeader(row.member)"
          >转移负责人</button>
        </form>

        <form v-if="account?.id === row.member.userId" class="member-actions" @submit.prevent="bindMine">
          <h3 class="panel-title">选择本项目使用的 SCM 身份</h3>
          <p v-if="bindingOptions.length === 0" class="field-hint">先到“账户与 SCM 身份”验证兼容身份。</p>
          <template v-else>
            <div class="field"><label :for="`identity-${row.member.userId}`">身份</label>
              <select :id="`identity-${row.member.userId}`" v-model="bindingIdentityId">
                <option v-for="identity in bindingOptions" :key="identity.id" :value="identity.id">
                  {{ identity.label }} · {{ identity.externalUsername }} · ID {{ identity.externalUserId }}
                </option>
              </select>
            </div>
            <div class="field"><label :for="`token-${row.member.userId}`">一次性 Token</label>
              <input :id="`token-${row.member.userId}`" v-model="bindingToken" type="password" required autocomplete="off" />
            </div>
            <button class="button button-primary" :disabled="pending">验证仓库权限并绑定</button>
          </template>
        </form>
      </li>
    </ul>

    <div v-if="projectId !== null" class="record-actions">
      <RouterLink class="button button-quiet" :to="requirementsRoute(projectId)">查看该项目需求</RouterLink>
    </div>
  </section>
</template>

<style scoped>
.batch-panel, .member-actions { display: grid; gap: var(--fp-space-4); }
.candidate-choice, .role-picker, .preview-row { display: flex; flex-wrap: wrap; gap: var(--fp-space-3); align-items: center; }
.member-grid { grid-template-columns: repeat(auto-fit, minmax(min(100%, 29rem), 1fr)); }
.member-card { display: flex; flex-direction: column; gap: var(--fp-space-4); }
.member-actions { padding-top: var(--fp-space-4); border-top: 0.0625rem solid var(--fp-color-border); }
</style>
