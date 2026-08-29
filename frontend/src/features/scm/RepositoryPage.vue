<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { parseId, PROJECT_QUERY_KEY } from "../../app/routes";
import { formatDateTime } from "../../lib/datetime";
import { apiErrorMessage } from "../../lib/http";
import {
  configureNotificationChannel,
  getNotificationChannel,
  removeNotificationChannel,
  type NotificationChannel,
} from "../notification/api";
import { hasProjectRole, listProjects, type Project } from "../project/api";
import {
  listScmRepositories,
  providerTokenPage,
  registerScmRepository,
  SCM_PROVIDER_DEFAULTS,
  SCM_PROVIDERS,
  SCM_REPOSITORY_TOKEN_SCOPES,
  SCM_TOKEN_PAGE_PATHS,
  updateScmRepository,
  type ScmProvider,
  type ScmRepository,
  type ScmRepositoryPatch,
} from "./api";

const route = useRoute();
const router = useRouter();
const projectId = computed(() => parseId(route.query[PROJECT_QUERY_KEY]));
const projects = ref<Project[]>([]);
const repository = ref<ScmRepository | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);
const pending = ref(false);
const provider = ref<ScmProvider>("GITHUB");
const externalId = ref("");
const apiBase = ref(SCM_PROVIDER_DEFAULTS.GITHUB);
const token = ref("");
const webhookSecret = ref("");
const identityApprovalRequired = ref(false);
const notification = ref<NotificationChannel | null>(null);
const notifyUrl = ref("");
const notifySecret = ref("");
const notifyEnabled = ref(true);
const notifyPending = ref(false);
const notifyError = ref<string | null>(null);
const selected = computed(
  () => projects.value.find((project) => project.id === projectId.value) ?? null,
);
const isLeader = computed(() => hasProjectRole(selected.value, "LEADER"));
const repositoryTokenPage = computed(() => providerTokenPage(provider.value, apiBase.value));

function choose(event: Event): void {
  const id = parseId((event.target as HTMLSelectElement).value);
  if (id !== null) {
    void router.push({ name: "repositories", query: { [PROJECT_QUERY_KEY]: String(id) } });
  }
}

watch(provider, (value) => {
  if (repository.value === null) apiBase.value = SCM_PROVIDER_DEFAULTS[value];
});

async function load(): Promise<void> {
  const id = projectId.value;
  repository.value = null;
  externalId.value = "";
  apiBase.value = SCM_PROVIDER_DEFAULTS.GITHUB;
  token.value = "";
  webhookSecret.value = "";
  identityApprovalRequired.value = false;
  notification.value = null;
  notifyError.value = null;
  error.value = null;
  if (id === null) return;
  loading.value = true;
  try {
    repository.value = (await listScmRepositories(id))[0] ?? null;
    if (repository.value !== null) {
      provider.value = repository.value.provider;
      externalId.value = repository.value.externalId;
      apiBase.value = repository.value.apiBase;
      identityApprovalRequired.value = repository.value.identityApprovalRequired;
    }
    // 通知配置只有 LEADER 读得到，非 LEADER 会拿到 403。那不是错误，是这一块
    // 不显示——所以它不进上面的 try，也不写 error。
    notification.value = await getNotificationChannel(id).catch(() => null);
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  } finally {
    loading.value = false;
  }
}

async function saveNotification(): Promise<void> {
  const id = projectId.value;
  if (id === null) return;
  notifyPending.value = true;
  notifyError.value = null;
  try {
    notification.value = await configureNotificationChannel(id, {
      webhookUrl: notifyUrl.value,
      secret: notifySecret.value,
      enabled: notifyEnabled.value,
    });
    notifyUrl.value = "";
    notifySecret.value = "";
  } catch (failure: unknown) {
    notifyError.value = apiErrorMessage(failure);
  } finally {
    notifyPending.value = false;
  }
}

async function dropNotification(): Promise<void> {
  const id = projectId.value;
  if (id === null) return;
  notifyPending.value = true;
  notifyError.value = null;
  try {
    await removeNotificationChannel(id);
    notification.value = { configured: false, enabled: false, updatedAt: null };
  } catch (failure: unknown) {
    notifyError.value = apiErrorMessage(failure);
  } finally {
    notifyPending.value = false;
  }
}

async function save(): Promise<void> {
  const id = projectId.value;
  if (id === null) return;
  pending.value = true;
  error.value = null;
  try {
    if (repository.value === null) {
      repository.value = await registerScmRepository(id, {
        provider: provider.value,
        externalId: externalId.value,
        apiBase: apiBase.value,
        token: token.value,
        webhookSecret: webhookSecret.value,
      });
    } else {
      const patch: ScmRepositoryPatch = {};
      if (externalId.value !== "") patch.externalId = externalId.value;
      if (apiBase.value !== "") patch.apiBase = apiBase.value;
      if (token.value !== "") patch.token = token.value;
      if (webhookSecret.value !== "") patch.webhookSecret = webhookSecret.value;
      patch.identityApprovalRequired = identityApprovalRequired.value;
      repository.value = await updateScmRepository(id, repository.value.id, patch);
    }
    token.value = "";
    webhookSecret.value = "";
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  } finally {
    pending.value = false;
  }
}

onMounted(async () => {
  try {
    projects.value = await listProjects();
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  }
});
watch(projectId, load, { immediate: true });
</script>
<template>
  <section aria-labelledby="repositories-title">
    <div class="page-head">
      <p class="eyebrow">SCM integration</p>
      <h1 id="repositories-title">仓库接入</h1>
      <p class="lede">
        一个项目接入一个 GitHub 或 GitLab 仓库。凭据仅写入请求，页面和读取接口都不会显示
        token 或 Webhook 密钥。
      </p>
    </div>
    <section class="panel project-selector">
      <div>
        <h2 class="panel-title">项目上下文</h2>
        <p class="field-hint">刷新会从服务端重新读取仓库的安全元数据。</p>
      </div>
      <div class="field">
        <label for="repository-project">当前项目</label>
        <select id="repository-project" :value="projectId ?? ''" @change="choose">
          <option value="" disabled>请选择项目</option>
          <option v-for="project in projects" :key="project.id" :value="project.id">
            {{ project.name }}
          </option>
        </select>
      </div>
    </section>

    <p v-if="projectId === null" class="empty-state">先选择项目。</p>
    <template v-else>
      <p v-if="loading" class="muted">正在读取仓库配置…</p>
      <p v-if="error" class="alert" role="alert">{{ error }}</p>
      <section
        v-if="repository"
        class="panel repository-current"
        aria-labelledby="repository-current-title"
      >
        <div class="index-head">
          <div>
            <p class="eyebrow">Safe repository metadata</p>
            <h2 id="repository-current-title" class="panel-title">当前接入</h2>
          </div>
          <span class="badge badge-success">已连接</span>
        </div>
        <dl class="meta-list">
          <div><dt>提供方</dt><dd>{{ repository.provider }}</dd></div>
          <div><dt>外部仓库 ID</dt><dd><code>{{ repository.externalId }}</code></dd></div>
          <div><dt>API 基地址</dt><dd><code>{{ repository.apiBase }}</code></dd></div>
          <div><dt>实例标识</dt><dd><code>{{ repository.instanceIdentity }}</code></dd></div>
          <div><dt>更新时间</dt><dd>{{ formatDateTime(repository.updatedAt) }}</dd></div>
        </dl>
      </section>
      <p v-else-if="!loading" class="empty-state">该项目还没有仓库接入。</p>
      <p v-if="!isLeader && selected" class="empty-state">
        你可查看安全元数据；只有项目负责人可以注册或修改仓库。
      </p>
      <section v-if="isLeader" class="panel repository-editor" aria-labelledby="repository-form-title">
        <div class="editor-heading">
          <p class="eyebrow">Repository credentials</p>
          <h2 id="repository-form-title" class="panel-title">
            {{ repository ? "修改仓库" : "注册仓库" }}
          </h2>
          <p class="field-hint">敏感凭据只写不回显；修改时留空表示保持原值。</p>
        </div>
        <form class="repository-form" @submit.prevent="save">
          <div v-if="!repository" class="field">
            <label for="repository-provider">提供方</label>
            <select id="repository-provider" v-model="provider">
              <option v-for="item in SCM_PROVIDERS" :key="item" :value="item">{{ item }}</option>
            </select>
          </div>
          <div class="field" :class="{ 'repository-span': repository }">
            <label for="repository-external-id">
              仓库外部 ID{{ repository ? "（留空不改）" : "" }}
            </label>
            <input
              id="repository-external-id"
              v-model="externalId"
              :required="!repository"
              maxlength="128"
            />
          </div>
          <div class="field repository-span">
            <label for="repository-api-base">
              API 基地址{{ repository ? "（留空不改）" : "" }}
            </label>
            <input
              id="repository-api-base"
              v-model="apiBase"
              :required="!repository"
              maxlength="512"
            />
          </div>
          <div class="field">
            <label for="repository-token">{{ repository ? "新的" : "" }}访问令牌</label>
            <input
              id="repository-token"
              v-model="token"
              type="password"
              autocomplete="off"
              :required="!repository"
            />
            <p class="field-hint">只写，不回显。</p>
            <p class="field-hint token-source">
              最小权限：<code>{{ SCM_REPOSITORY_TOKEN_SCOPES[provider] }}</code> ·
              <a
                v-if="repositoryTokenPage"
                :href="repositoryTokenPage"
                target="_blank"
                rel="noreferrer"
              >{{ provider }} Token 创建页</a>
              <span v-else>
                在你的实例上打开 <code>{{ SCM_TOKEN_PAGE_PATHS[provider] }}</code> 创建 Token
              </span>
            </p>
          </div>
          <div class="field">
            <label for="repository-webhook">
              {{ repository ? "新的" : "" }} Webhook 密钥
            </label>
            <input
              id="repository-webhook"
              v-model="webhookSecret"
              type="password"
              autocomplete="off"
              :required="!repository"
            />
            <p class="field-hint">只写，不回显。</p>
          </div>
          <label v-if="repository" class="checkbox-field">
            <input v-model="identityApprovalRequired" type="checkbox" />
            <span>成员 SCM 身份通过仓库验证后仍需负责人批准</span>
          </label>
          <div class="form-actions repository-actions">
            <button class="button button-primary" :disabled="pending">
              {{ pending ? "正在保存…" : repository ? "保存修改" : "注册仓库" }}
            </button>
          </div>
        </form>
      </section>

      <section
        v-if="isLeader && notification"
        class="panel notification-editor"
        aria-labelledby="notification-title"
      >
        <div class="editor-heading">
          <p class="eyebrow">DingTalk notifications</p>
          <h2 id="notification-title" class="panel-title">审查完成通知</h2>
          <p class="field-hint">
            一次审查跑完后往群里发一条摘要：PR、关联需求、发现条数。
            <strong>不含</strong> finding 正文与 diff——群成员未必是项目成员。
          </p>
          <p class="field-hint">
            推送失败只记日志，不会影响审查本身。
          </p>
        </div>
        <form class="repository-form" @submit.prevent="saveNotification">
          <p v-if="notifyError" class="form-error" role="alert">{{ notifyError }}</p>
          <div class="field repository-span">
            <label for="notify-url">
              Webhook 地址{{ notification.configured ? "（重填即替换）" : "" }}
            </label>
            <input
              id="notify-url"
              v-model="notifyUrl"
              type="password"
              autocomplete="off"
              required
              maxlength="512"
              placeholder="https://oapi.dingtalk.com/robot/send?access_token=…"
            />
            <p class="field-hint">
              这个地址本身就是凭据：<code>access_token</code> 就在里面。只写，不回显。
            </p>
          </div>
          <div class="field repository-span">
            <label for="notify-secret">加签密钥</label>
            <input
              id="notify-secret"
              v-model="notifySecret"
              type="password"
              autocomplete="off"
              required
              maxlength="256"
            />
            <p class="field-hint">
              机器人安全设置里选「加签」，把那串 <code>SEC…</code> 填这里。
              只有加签能在地址泄露后仍然拦住伪造。
            </p>
          </div>
          <label class="checkbox-field">
            <input v-model="notifyEnabled" type="checkbox" />
            <span>启用推送</span>
          </label>
          <dl v-if="notification.configured" class="meta-list">
            <div><dt>状态</dt><dd>{{ notification.enabled ? "已启用" : "已停用" }}</dd></div>
            <div v-if="notification.updatedAt">
              <dt>更新时间</dt><dd>{{ formatDateTime(notification.updatedAt) }}</dd>
            </div>
          </dl>
          <div class="form-actions repository-actions">
            <button class="button button-primary" :disabled="notifyPending">
              {{ notifyPending ? "正在保存…" : notification.configured ? "替换凭据" : "保存" }}
            </button>
            <button
              v-if="notification.configured"
              type="button"
              class="button button-danger"
              :disabled="notifyPending"
              @click="dropNotification"
            >
              移除
            </button>
          </div>
        </form>
      </section>
    </template>
  </section>
</template>

<style scoped>
.repository-current,
.notification-editor,
.repository-editor {
  border-color: var(--fp-color-border-accent);
}

.repository-editor,
.notification-editor {
  display: grid;
  align-items: start;
  gap: var(--fp-space-8);
  grid-template-columns: minmax(14rem, 0.42fr) minmax(0, 1fr);
}

.editor-heading .panel-title {
  margin-bottom: var(--fp-space-2);
}

/* start 而非 end：同行两格按顶部对齐，标签与输入框才会落在同一水平线。
   end 会让「访问令牌」那两条 hint（含会换行的长 token-source）把同行的
   Webhook 输入框整体压低。高度差留在底部是无害的。 */
.repository-form {
  display: grid;
  align-items: start;
  gap: var(--fp-space-4);
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.repository-span,
.repository-actions {
  grid-column: 1 / -1;
}

/* 不带 .field：.field 是 display: grid，会把勾选框和它的文字排成上下两行。 */
.checkbox-field {
  display: flex;
  grid-column: 1 / -1;
  align-items: center;
  gap: var(--fp-space-3);
  color: var(--fp-color-text-muted);
}

.checkbox-field input {
  width: auto;
  flex: 0 0 auto;
}

/* 自建实例分支会渲染 /-/user_settings/personal_access_tokens 这种长路径，
   390px 下不断词就会撑出页面级横向滚动。 */
.token-source {
  word-break: break-word;
}

@media (max-width: 64rem) {
  .repository-editor {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 42rem) {
  .repository-form {
    grid-template-columns: 1fr;
  }

  .repository-span,
  .repository-actions {
    grid-column: auto;
  }
}
</style>
