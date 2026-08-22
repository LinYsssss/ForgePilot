<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink, useRoute } from "vue-router";

import { parseId, projectMembersRoute, requirementsRoute } from "../../app/routes";
import { formatDateTime } from "../../lib/datetime";
import { apiErrorMessage } from "../../lib/http";
import {
  checkQuality,
  listRequirements,
  type QualityReport,
  type RequirementSummary,
} from "../requirement/api";
import {
  registerScmRepository,
  updateScmRepository,
  SCM_PROVIDERS,
  type ScmProvider,
  type ScmRepository,
  type ScmRepositoryPatch,
} from "../scm/api";
import { getProject, PROJECT_ROLE_LABELS, type Project } from "./api";

const route = useRoute();
const projectId = computed(() => parseId(route.params.id));

const project = ref<Project | null>(null);
const requirements = ref<RequirementSummary[]>([]);
const loading = ref(true);
const loadError = ref<string | null>(null);

const isLeader = computed(() => project.value?.myRole === "LEADER");

const registerProvider = ref<ScmProvider>("GITHUB");
const registerExternalId = ref("");
const registerApiBase = ref("https://api.github.com");
const registerToken = ref("");
const registerWebhookSecret = ref("");
const registerPending = ref(false);
const registerError = ref<string | null>(null);

const updateRepositoryId = ref("");
const updateExternalId = ref("");
const updateApiBase = ref("");
const updateToken = ref("");
const updateWebhookSecret = ref("");
const updatePending = ref(false);
const updateError = ref<string | null>(null);

/**
 * Only what the last write returned. There is no read endpoint for the SCM
 * connection, so a reload starts blank rather than showing a stale or invented
 * connection state.
 */
const repository = ref<ScmRepository | null>(null);

const qualitySelection = ref<number | null>(null);
const qualityPending = ref(false);
const qualityError = ref<string | null>(null);
const qualityReport = ref<QualityReport | null>(null);

// Watched rather than mounted: vue-router reuses this component when only the
// `:id` changes, so an `onMounted` load would keep showing the previous project.
watch(
  projectId,
  async (id) => {
    loading.value = true;
    loadError.value = null;
    project.value = null;
    requirements.value = [];
    repository.value = null;
    qualityReport.value = null;
    qualitySelection.value = null;
    if (id === null) {
      loading.value = false;
      return;
    }
    try {
      const [loadedProject, loadedRequirements] = await Promise.all([
        getProject(id),
        listRequirements(id),
      ]);
      project.value = loadedProject;
      requirements.value = loadedRequirements;
    } catch (failure: unknown) {
      loadError.value = apiErrorMessage(failure);
    } finally {
      loading.value = false;
    }
  },
  { immediate: true },
);

async function register(): Promise<void> {
  const id = projectId.value;
  if (id === null) {
    return;
  }
  registerPending.value = true;
  registerError.value = null;
  try {
    repository.value = await registerScmRepository(id, {
      provider: registerProvider.value,
      externalId: registerExternalId.value,
      apiBase: registerApiBase.value,
      token: registerToken.value,
      webhookSecret: registerWebhookSecret.value,
    });
    // Credentials never live longer than the request that carried them.
    registerToken.value = "";
    registerWebhookSecret.value = "";
    updateRepositoryId.value = String(repository.value.id);
  } catch (failure: unknown) {
    registerError.value = apiErrorMessage(failure);
  } finally {
    registerPending.value = false;
  }
}

async function update(): Promise<void> {
  const id = projectId.value;
  const repositoryId = parseId(updateRepositoryId.value);
  if (id === null) {
    return;
  }
  if (repositoryId === null) {
    updateError.value = "请先填写要修改的仓库记录 id。";
    return;
  }
  const patch: ScmRepositoryPatch = {};
  if (updateExternalId.value !== "") {
    patch.externalId = updateExternalId.value;
  }
  if (updateApiBase.value !== "") {
    patch.apiBase = updateApiBase.value;
  }
  if (updateToken.value !== "") {
    patch.token = updateToken.value;
  }
  if (updateWebhookSecret.value !== "") {
    patch.webhookSecret = updateWebhookSecret.value;
  }
  updatePending.value = true;
  updateError.value = null;
  try {
    repository.value = await updateScmRepository(id, repositoryId, patch);
    updateToken.value = "";
    updateWebhookSecret.value = "";
  } catch (failure: unknown) {
    updateError.value = apiErrorMessage(failure);
  } finally {
    updatePending.value = false;
  }
}

async function runQualityCheck(): Promise<void> {
  const id = projectId.value;
  const requirementId = qualitySelection.value;
  if (id === null || requirementId === null) {
    return;
  }
  qualityPending.value = true;
  qualityError.value = null;
  try {
    qualityReport.value = await checkQuality(id, requirementId);
  } catch (failure: unknown) {
    qualityError.value = apiErrorMessage(failure);
  } finally {
    qualityPending.value = false;
  }
}
</script>

<template>
  <section aria-labelledby="project-settings-title">
    <div class="page-head">
      <p class="eyebrow">Project · settings</p>
      <h1 id="project-settings-title">{{ project ? project.name : "项目设置" }}</h1>
      <p v-if="project" class="muted">
        我的角色：{{ PROJECT_ROLE_LABELS[project.myRole] }}
      </p>
      <div v-if="projectId !== null" class="record-actions">
        <RouterLink class="button" :to="projectMembersRoute(projectId)">成员管理</RouterLink>
        <RouterLink class="button" :to="requirementsRoute(projectId)">研发需求</RouterLink>
      </div>
    </div>

    <p v-if="projectId === null" class="alert" role="alert">路由缺少有效的项目 id。</p>
    <p v-else-if="loading" class="muted">正在加载项目设置…</p>
    <p v-else-if="loadError" class="alert" role="alert">{{ loadError }}</p>

    <template v-else>
      <section class="panel" aria-labelledby="scm-title">
        <h2 id="scm-title" class="panel-title">SCM 仓库配置</h2>
        <p class="field-hint">
          访问令牌与 webhook 密钥是只写字段：服务端从不回显，本页也不保存它们。
        </p>

        <p v-if="!isLeader" class="empty-state">只有项目负责人可以配置 SCM 仓库。</p>

        <template v-else>
          <dl v-if="repository" class="meta-list scm-repository">
            <div>
              <dt>仓库记录 id</dt>
              <dd class="scm-repository-id">{{ repository.id }}</dd>
            </div>
            <div>
              <dt>提供方</dt>
              <dd>{{ repository.provider }}</dd>
            </div>
            <div>
              <dt>实例标识</dt>
              <dd><code>{{ repository.instanceIdentity }}</code></dd>
            </div>
            <div>
              <dt>仓库外部 id</dt>
              <dd><code>{{ repository.externalId }}</code></dd>
            </div>
            <div>
              <dt>API 基地址</dt>
              <dd><code>{{ repository.apiBase }}</code></dd>
            </div>
            <div>
              <dt>更新时间</dt>
              <dd>{{ formatDateTime(repository.updatedAt) }}</dd>
            </div>
          </dl>
          <p v-else class="empty-state">
            本页只显示本次写入返回的连接信息。服务端没有查询端点，刷新后这里会重新变空，
            这不代表仓库没有配置过。
          </p>

          <h3 class="subsection-title">注册仓库</h3>
          <form class="inline-form" @submit.prevent="register">
            <div class="field">
              <label for="scm-provider">提供方</label>
              <select id="scm-provider" v-model="registerProvider">
                <option v-for="provider in SCM_PROVIDERS" :key="provider" :value="provider">
                  {{ provider }}
                </option>
              </select>
            </div>
            <div class="field">
              <label for="scm-external-id">仓库外部 id</label>
              <input id="scm-external-id" v-model="registerExternalId" required maxlength="128" />
            </div>
            <div class="field">
              <label for="scm-api-base">API 基地址</label>
              <input id="scm-api-base" v-model="registerApiBase" required maxlength="512" />
            </div>
            <div class="field">
              <label for="scm-token">访问令牌</label>
              <input
                id="scm-token"
                v-model="registerToken"
                type="password"
                autocomplete="off"
                required
              />
              <p class="field-hint">只写，不回显。</p>
            </div>
            <div class="field">
              <label for="scm-webhook-secret">Webhook 密钥</label>
              <input
                id="scm-webhook-secret"
                v-model="registerWebhookSecret"
                type="password"
                autocomplete="off"
                required
              />
              <p class="field-hint">只写，不回显。</p>
            </div>
            <button type="submit" class="button button-primary" :disabled="registerPending">
              注册仓库
            </button>
          </form>
          <p v-if="registerError" class="alert" role="alert">{{ registerError }}</p>

          <h3 class="subsection-title">修改仓库</h3>
          <form class="inline-form" @submit.prevent="update">
            <div class="field">
              <label for="scm-update-id">仓库记录 id</label>
              <input
                id="scm-update-id"
                v-model="updateRepositoryId"
                type="number"
                min="1"
                step="1"
                inputmode="numeric"
              />
            </div>
            <div class="field">
              <label for="scm-update-external-id">仓库外部 id</label>
              <input id="scm-update-external-id" v-model="updateExternalId" maxlength="128" />
            </div>
            <div class="field">
              <label for="scm-update-api-base">API 基地址</label>
              <input id="scm-update-api-base" v-model="updateApiBase" maxlength="512" />
            </div>
            <div class="field">
              <label for="scm-update-token">新的访问令牌</label>
              <input
                id="scm-update-token"
                v-model="updateToken"
                type="password"
                autocomplete="off"
              />
            </div>
            <div class="field">
              <label for="scm-update-webhook-secret">新的 Webhook 密钥</label>
              <input
                id="scm-update-webhook-secret"
                v-model="updateWebhookSecret"
                type="password"
                autocomplete="off"
              />
            </div>
            <button type="submit" class="button button-primary" :disabled="updatePending">
              保存修改
            </button>
            <p class="field-hint">留空的字段不改动，填了的字段才会覆盖。</p>
          </form>
          <p v-if="updateError" class="alert" role="alert">{{ updateError }}</p>
        </template>
      </section>

      <section class="panel" aria-labelledby="knowledge-title">
        <h2 id="knowledge-title" class="panel-title">项目知识</h2>
        <p class="empty-state knowledge-unavailable">
          本页暂不提供项目知识上传与解析状态：服务端还没有对应的 HTTP 端点，
          批次 2 的知识能力只有服务层。这里如实留白，不放一个点了没有反应的按钮。
        </p>
      </section>

      <section class="panel" aria-labelledby="quality-title">
        <h2 id="quality-title" class="panel-title">需求质量检查</h2>
        <p class="field-hint">
          结果归属被检查的那个需求版本；草稿正文一改就作废。质量检查是建议，不推进任何状态。
        </p>

        <p v-if="!isLeader" class="empty-state">只有项目负责人可以运行需求质量检查。</p>
        <p v-else-if="requirements.length === 0" class="empty-state">该项目还没有需求。</p>

        <template v-else>
          <form class="inline-form" @submit.prevent="runQualityCheck">
            <div class="field">
              <label for="quality-requirement">选择需求</label>
              <select id="quality-requirement" v-model="qualitySelection">
                <option :value="null" disabled>请选择需求</option>
                <option v-for="item in requirements" :key="item.id" :value="item.id">
                  {{ item.title }}
                </option>
              </select>
            </div>
            <button
              type="submit"
              class="button button-primary"
              :disabled="qualityPending || qualitySelection === null"
            >
              运行质量检查
            </button>
          </form>
          <p v-if="qualityError" class="alert" role="alert">{{ qualityError }}</p>

          <div v-if="qualityReport" class="quality-report">
            <dl class="meta-list">
              <div>
                <dt>检查的版本</dt>
                <dd class="quality-revision">
                  v{{ qualityReport.revisionSeq }}（版本 {{ qualityReport.revisionId }}）
                </dd>
              </div>
              <div>
                <dt>规则集版本</dt>
                <dd>{{ qualityReport.qualityVersion }}</dd>
              </div>
              <div>
                <dt>检查时间</dt>
                <dd>{{ formatDateTime(qualityReport.checkedAt) }}</dd>
              </div>
            </dl>

            <h3 class="subsection-title">确定性规则</h3>
            <p v-if="qualityReport.rules.length === 0" class="muted">规则没有发现问题。</p>
            <ul v-else class="quality-list">
              <li v-for="(rule, index) in qualityReport.rules" :key="index">
                <span class="badge badge-warning">{{ rule.rule }}</span>
                <span v-if="rule.acKey" class="badge badge-neutral">{{ rule.acKey }}</span>
                {{ rule.message }}
              </li>
            </ul>

            <h3 class="subsection-title">AI 结构化评估</h3>
            <p v-if="qualityReport.ai === null" class="muted">本次没有返回 AI 评估。</p>
            <template v-else>
              <p class="muted">{{ qualityReport.ai.summary ?? "AI 没有给出总结。" }}</p>
              <p v-if="qualityReport.ai.issues.length === 0" class="muted">AI 没有发现问题。</p>
              <ul v-else class="quality-list">
                <li v-for="(issue, index) in qualityReport.ai.issues" :key="index">
                  <span v-if="issue.acKey" class="badge badge-neutral">{{ issue.acKey }}</span>
                  {{ issue.message }}
                </li>
              </ul>
            </template>
          </div>
        </template>
      </section>
    </template>
  </section>
</template>

<style scoped>
.subsection-title {
  margin: var(--fp-space-6) 0 var(--fp-space-2);
  font-size: 0.9375rem;
}

.quality-report {
  margin-top: var(--fp-space-4);
}

.quality-list {
  display: grid;
  gap: var(--fp-space-2);
  margin: var(--fp-space-2) 0 0;
  padding: 0;
  list-style: none;
  line-height: 1.6;
}
</style>
