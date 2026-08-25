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
  PROJECT_ROLES,
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

/** 后端 `BatchRequest.members` 的 `@Size(max = 50)`；越界在提交前就拦住。 */
const BATCH_LIMIT = 50;
/** 后端 `ProjectMemberService.search` 的下限；低于它请求必然 422。 */
const QUERY_MIN_LENGTH = 2;

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
const searchHint = ref<string | null>(null);
const failedRow = ref<number | null>(null);
const memberSearch = ref("");
const memberRoleFilter = ref<ProjectRole | "">("");
const commonRoles = ref<ProjectRole[]>(["DEVELOPER"]);
const loading = ref(true);
const pending = ref(false);
const error = ref<string | null>(null);

const isLeader = computed(() => hasProjectRole(project.value, "LEADER"));
const isMember = computed(
  () => members.value.some((member) => member.userId === account.value?.id),
);
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
  const text = query.value.trim();
  if (id === null) return;
  // 后端对纯数字放行单字符（按平台 ID 精确查找），前端提示与它保持同一条件。
  const numericId = text.length > 0 && /^\d+$/.test(text);
  if (text.length < QUERY_MIN_LENGTH && !numericId) {
    searchHint.value = `至少输入 ${QUERY_MIN_LENGTH} 个字符，或直接输入平台 ID。`;
    return;
  }
  searchHint.value = null;
  pending.value = true;
  error.value = null;
  try {
    candidates.value = await searchMemberCandidates(id, text);
    if (candidates.value.length === 0) {
      searchHint.value = "没有匹配的账号。";
    }
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  } finally {
    pending.value = false;
  }
}

function canSelect(candidate: MemberCandidate): boolean {
  return !candidate.alreadyMember && candidate.enabled;
}

function candidateState(candidate: MemberCandidate): { label: string; tone: string } {
  if (candidate.alreadyMember) return { label: "已是成员", tone: "neutral" };
  if (!candidate.enabled) return { label: "账号已停用", tone: "warning" };
  return { label: "可添加", tone: "success" };
}

function isSelected(userId: number): boolean {
  return selected.value.some((row) => row.candidate.userId === userId);
}

function toggleCandidate(candidate: MemberCandidate): void {
  if (!canSelect(candidate)) return;
  const existing = selected.value.findIndex((row) => row.candidate.userId === candidate.userId);
  if (existing >= 0) {
    selected.value.splice(existing, 1);
    failedRow.value = null;
    return;
  }
  if (selected.value.length >= BATCH_LIMIT) {
    error.value = `单次最多添加 ${BATCH_LIMIT} 人，请分批提交。`;
    return;
  }
  selected.value.push({ candidate, roles: [...commonRoles.value] });
}

function applyCommonRoles(): void {
  selected.value.forEach((row) => { row.roles = [...commonRoles.value]; });
}

function toggleRole(roles: ProjectRole[], role: ProjectRole): void {
  const index = roles.indexOf(role);
  if (index >= 0) roles.splice(index, 1);
  else roles.push(role);
}

const emptyRoleRows = computed(
  () => selected.value.filter((row) => row.roles.length === 0).length,
);
const canSubmitBatch = computed(
  () => selected.value.length > 0
    && selected.value.length <= BATCH_LIMIT
    && emptyRoleRows.value === 0,
);

/** 后端逐行校验失败时返回 `Member row {index} ...`；解析不出来就只显示原文。 */
function locateFailedRow(message: string): number | null {
  const match = /^Member row (\d+)\b/.exec(message);
  return match === null ? null : Number(match[1]);
}

async function addSelected(): Promise<void> {
  const id = projectId.value;
  if (id === null || !canSubmitBatch.value) return;
  pending.value = true;
  error.value = null;
  failedRow.value = null;
  try {
    await addMembers(id, selected.value.map((row) => ({
      userId: row.candidate.userId,
      roles: row.roles,
    })));
    selected.value = [];
    candidates.value = [];
    await load();
  } catch (failure: unknown) {
    const message = apiErrorMessage(failure);
    failedRow.value = locateFailedRow(message);
    error.value = message;
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

const visibleMembers = computed(() => {
  const text = memberSearch.value.trim().toLowerCase();
  const role = memberRoleFilter.value;
  return memberRows.value.filter((row) => {
    const matchesText = text === ""
      || row.member.displayName.toLowerCase().includes(text)
      || row.member.username.toLowerCase().includes(text)
      || String(row.member.userId).includes(text);
    return matchesText && (role === "" || row.member.roles.includes(role));
  });
});

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
      <p class="field-hint">
        单次最多 {{ BATCH_LIMIT }} 人，整批提交：任一行不合法则整批不生效，不会留下半套成员关系。
        负责人不在批量路径内，只能通过明确确认的转移动作变更。
      </p>
      <form class="inline-form" @submit.prevent="search">
        <div class="field">
          <label for="candidate-query">显示名、用户名或平台 ID</label>
          <input id="candidate-query" v-model="query" required minlength="2" maxlength="120" />
        </div>
        <button class="button button-quiet" :disabled="pending">搜索</button>
      </form>
      <p v-if="searchHint" class="field-hint" role="status">{{ searchHint }}</p>

      <div v-if="candidates.length" class="table-scroll">
        <table class="data-table candidate-table">
          <caption class="table-caption">勾选后可批量套用共同角色，也可逐行覆盖。</caption>
          <thead>
            <tr>
              <th scope="col">选择</th>
              <th scope="col">成员</th>
              <th scope="col">平台 ID</th>
              <th scope="col">状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="candidate in candidates" :key="candidate.userId">
              <td class="candidate-choice">
                <input
                  type="checkbox"
                  :aria-label="`选择 ${candidate.displayName}`"
                  :checked="isSelected(candidate.userId)"
                  :disabled="!canSelect(candidate)"
                  @change="toggleCandidate(candidate)"
                />
              </td>
              <td>
                <strong>{{ candidate.displayName }}</strong><br />
                <span class="muted">@{{ candidate.username }}</span>
              </td>
              <td><code>{{ candidate.userId }}</code></td>
              <td>
                <span :class="['badge', `badge-${candidateState(candidate).tone}`]">
                  {{ candidateState(candidate).label }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

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
        <div class="table-scroll">
          <table class="data-table preview-table">
            <caption class="table-caption">
              提交预览：共 {{ selected.length }} 人，逐行角色可覆盖共同角色。
            </caption>
            <thead>
              <tr>
                <th scope="col">成员</th>
                <th scope="col">平台 ID</th>
                <th scope="col">角色</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(row, index) in selected"
                :key="row.candidate.userId"
                :class="{ 'row-error': index === failedRow }"
              >
                <td>
                  <strong>{{ row.candidate.displayName }}</strong><br />
                  <span class="muted">@{{ row.candidate.username }}</span>
                </td>
                <td><code>{{ row.candidate.userId }}</code></td>
                <td class="preview-roles">
                  <label v-for="role in assignableRoles" :key="role">
                    <input
                      type="checkbox"
                      :checked="row.roles.includes(role)"
                      @change="toggleRole(row.roles, role)"
                    /> {{ PROJECT_ROLE_LABELS[role] }}
                  </label>
                  <span v-if="row.roles.length === 0" class="badge badge-warning">
                    至少选一个角色
                  </span>
                  <span v-if="index === failedRow" class="badge badge-danger">本行被服务端拒绝</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-if="emptyRoleRows > 0" class="field-hint" role="status">
          有 {{ emptyRoleRows }} 行没有角色；成员至少需要一个角色才能提交。
        </p>
        <button
          class="button button-primary"
          :disabled="pending || !canSubmitBatch"
          @click="addSelected"
        >
          一次添加 {{ selected.length }} 人
        </button>
      </template>
    </section>

    <section
      v-if="!loading"
      class="panel member-directory"
      aria-labelledby="member-directory-title"
    >
      <h2 id="member-directory-title" class="panel-title">项目成员（{{ members.length }}）</h2>
      <p class="field-hint">
        同一成员可同时拥有开发与评审角色，权限取能力并集；SCM 绑定状态来自真实核验记录。
      </p>
      <div class="member-filters" aria-label="筛选成员">
        <div class="field">
          <label for="member-search">搜索</label>
          <input
            id="member-search"
            v-model="memberSearch"
            type="search"
            placeholder="显示名、用户名或平台 ID"
          />
        </div>
        <div class="field">
          <label for="member-role-filter">角色</label>
          <select id="member-role-filter" v-model="memberRoleFilter">
            <option value="">全部</option>
            <option v-for="role in PROJECT_ROLES" :key="role" :value="role">
              {{ PROJECT_ROLE_LABELS[role] }}
            </option>
          </select>
        </div>
      </div>

      <p v-if="members.length === 0" class="empty-state">该项目还没有成员。</p>
      <p v-else-if="visibleMembers.length === 0" class="empty-state">
        没有符合当前筛选条件的成员。
      </p>
      <div v-else class="table-scroll">
        <table class="data-table member-table">
          <caption class="table-caption">按服务端返回的加入顺序展示。</caption>
          <thead>
            <tr>
              <th scope="col">成员</th>
              <th scope="col">角色</th>
              <th scope="col">SCM 绑定</th>
              <th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in visibleMembers" :key="row.member.userId">
              <td>
                <strong>{{ row.member.displayName }}</strong><br />
                <span class="muted">@{{ row.member.username }} · ID {{ row.member.userId }}</span>
              </td>
              <td class="member-roles">
                <span v-for="role in row.member.roles" :key="role" class="badge badge-info">
                  {{ PROJECT_ROLE_LABELS[role] }}
                </span>
              </td>
              <td>
                <template v-if="row.binding">
                  <span class="badge badge-neutral">{{ row.binding.status }}</span><br />
                  <span class="muted">
                    {{ row.binding.label }} · {{ row.binding.usageType }} ·
                    {{ row.binding.externalUsername }} · ID {{ row.binding.externalUserId }}
                  </span><br />
                  <span class="muted">
                    仓库权限 {{ row.binding.accessLevel ?? "待验证" }} · 核验
                    {{
                      row.binding.accessCheckedAt
                        ? formatDateTime(row.binding.accessCheckedAt)
                        : "待确认"
                    }}
                  </span>
                  <span
                    v-if="isLeader && row.binding.status === 'PENDING_APPROVAL'"
                    class="record-actions"
                  >
                    <button
                      type="button"
                      class="button button-primary"
                      @click="decide(row.binding, 'approve')"
                    >批准</button>
                    <button
                      type="button"
                      class="button button-quiet"
                      @click="decide(row.binding, 'reject')"
                    >拒绝</button>
                  </span>
                </template>
                <span v-else class="muted">
                  {{
                    row.member.roles.includes("DEVELOPER")
                      ? "等待成员绑定 SCM 身份"
                      : "当前角色无需绑定 SCM 身份"
                  }}
                </span>
              </td>
              <td>
                <details v-if="isLeader" class="row-editor">
                  <summary>编辑角色</summary>
                  <form class="row-editor-form" @submit.prevent="saveRoles(row.member)">
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
                </details>
                <span v-else class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!--
      这个表单永远只对当前登录成员自己成立，因此它是一个单例，不该藏在成员
      表格的某一行里。SCM 身份归用户本人，负责人不能替别人选。
    -->
    <section
      v-if="!loading && isMember"
      class="panel binding-panel"
      aria-labelledby="binding-title"
    >
      <h2 id="binding-title" class="panel-title">我在本项目使用的 SCM 身份</h2>
      <p class="field-hint">
        一次性 Token 只用于核验你对该仓库的权限，不保存、不回显、不进日志。
      </p>
      <p v-if="bindingOptions.length === 0" class="empty-state">
        先到“账户与 SCM 身份”验证与本项目仓库兼容的身份。
      </p>
      <form v-else class="identity-binding-form" @submit.prevent="bindMine">
        <div class="field">
          <label for="binding-identity">身份</label>
          <select id="binding-identity" v-model="bindingIdentityId">
            <option v-for="identity in bindingOptions" :key="identity.id" :value="identity.id">
              {{ identity.label }} · {{ identity.externalUsername }} · ID {{ identity.externalUserId }}
            </option>
          </select>
        </div>
        <div class="field">
          <label for="binding-token">一次性 Token</label>
          <input id="binding-token" v-model="bindingToken" type="password" required autocomplete="off" />
        </div>
        <button class="button button-primary" :disabled="pending">验证仓库权限并绑定</button>
      </form>
    </section>

    <div v-if="projectId !== null" class="record-actions">
      <RouterLink class="button button-quiet" :to="requirementsRoute(projectId)">查看该项目需求</RouterLink>
    </div>
  </section>
</template>

<style scoped>
.batch-panel { display: grid; gap: var(--fp-space-4); }
.role-picker, .preview-roles, .member-roles, .row-editor-form { display: flex; flex-wrap: wrap; gap: var(--fp-space-3); align-items: center; }
.member-filters { display: grid; gap: var(--fp-space-4); grid-template-columns: minmax(15rem, 1.4fr) minmax(10rem, 0.6fr); }
.member-table td, .candidate-table td, .preview-table td { min-width: 0; }
.row-editor > summary { cursor: pointer; }
.row-editor-form { margin-top: var(--fp-space-3); }
.identity-binding-form { display: grid; gap: var(--fp-space-4); grid-template-columns: repeat(auto-fit, minmax(min(100%, 14rem), 1fr)); align-items: end; }
.row-error { box-shadow: inset 0.1875rem 0 0 var(--fp-color-danger); }
.batch-panel .button-primary { justify-self: start; }

@media (max-width: 42rem) {
  .member-filters { grid-template-columns: 1fr; }
}
</style>
