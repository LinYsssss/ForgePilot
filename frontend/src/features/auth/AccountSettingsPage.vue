<script setup lang="ts">
import { computed, onMounted, ref } from "vue";

import { apiErrorMessage } from "../../lib/http";
import {
  listScmIdentities,
  providerTokenPage,
  revokeScmIdentity,
  SCM_IDENTITY_TOKEN_SCOPES,
  SCM_PROVIDER_DEFAULTS,
  SCM_TOKEN_PAGE_PATHS,
  updateScmIdentity,
  verifyScmIdentity,
  type ScmIdentity,
  type ScmIdentityUsage,
  type ScmProvider,
} from "../scm/api";
import { changePassword, changeDisplayName, useSession } from "./session";

const { account } = useSession();
const displayName = ref("");
const identities = ref<ScmIdentity[]>([]);
const identityDrafts = ref<Record<string, { label: string; usageType: ScmIdentityUsage }>>({});
const provider = ref<ScmProvider>("GITHUB");
const apiBase = ref(SCM_PROVIDER_DEFAULTS.GITHUB);
const label = ref("");
const usageType = ref<ScmIdentityUsage>("WORK");
const oneTimeToken = ref("");
const pending = ref(false);
const message = ref<string | null>(null);
const error = ref<string | null>(null);

const identityTokenPage = computed(() => providerTokenPage(provider.value, apiBase.value));

const currentPassword = ref("");
const newPassword = ref("");
const passwordConfirmation = ref("");
const passwordPending = ref(false);
const passwordError = ref<string | null>(null);
const passwordMessage = ref<string | null>(null);

async function updatePassword(): Promise<void> {
  passwordError.value = null;
  passwordMessage.value = null;
  if (newPassword.value !== passwordConfirmation.value) {
    passwordError.value = "两次输入的新密码不一致。";
    return;
  }
  passwordPending.value = true;
  try {
    await changePassword(currentPassword.value, newPassword.value);
    currentPassword.value = "";
    newPassword.value = "";
    passwordConfirmation.value = "";
    passwordMessage.value = "密码已修改，其他已登录会话将失效。";
  } catch (failure: unknown) {
    passwordError.value = apiErrorMessage(failure);
  } finally {
    passwordPending.value = false;
  }
}

function resetIdentityDrafts(): void {
  identityDrafts.value = Object.fromEntries(identities.value.map((identity) => [
    String(identity.id),
    { label: identity.label, usageType: identity.usageType },
  ]));
}

onMounted(async () => {
  displayName.value = account.value?.displayName ?? "";
  try {
    identities.value = await listScmIdentities();
    resetIdentityDrafts();
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  }
});

async function saveProfile(): Promise<void> {
  pending.value = true;
  error.value = null;
  try {
    await changeDisplayName(displayName.value);
    message.value = "显示名已更新。";
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  } finally {
    pending.value = false;
  }
}

async function verifyIdentity(): Promise<void> {
  pending.value = true;
  error.value = null;
  try {
    const identity = await verifyScmIdentity({
      provider: provider.value,
      apiBase: apiBase.value,
      oneTimeToken: oneTimeToken.value,
      label: label.value,
      usageType: usageType.value,
    });
    identities.value = [...identities.value.filter((item) => item.id !== identity.id), identity];
    resetIdentityDrafts();
    label.value = "";
    oneTimeToken.value = "";
    message.value = "SCM 身份已通过 Provider 验证；Token 未被保存。";
  } catch (failure: unknown) {
    oneTimeToken.value = "";
    error.value = apiErrorMessage(failure);
  } finally {
    pending.value = false;
  }
}

async function revoke(identity: ScmIdentity): Promise<void> {
  await revokeScmIdentity(identity.id);
  identities.value = await listScmIdentities();
  resetIdentityDrafts();
}

async function saveIdentity(identity: ScmIdentity): Promise<void> {
  const draft = identityDrafts.value[String(identity.id)]!;
  const updated = await updateScmIdentity(identity.id, draft.label, draft.usageType);
  identities.value = identities.value.map((item) => item.id === updated.id ? updated : item);
  resetIdentityDrafts();
  message.value = "SCM 身份标签和用途已更新。";
}
</script>

<template>
  <section aria-labelledby="account-title">
    <div class="page-head">
      <p class="eyebrow">Account identity</p>
      <h1 id="account-title">账户与 SCM 身份</h1>
      <p v-if="account" class="lede">@{{ account.username }} · 平台 ID {{ account.id }}</p>
    </div>

    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <p v-if="message" class="muted" role="status">{{ message }}</p>

    <form class="panel settings-form" @submit.prevent="saveProfile">
      <h2 class="panel-title">显示名</h2>
      <div class="field">
        <label for="profile-display-name">姓名或常用称呼</label>
        <input id="profile-display-name" v-model="displayName" required maxlength="120" />
      </div>
      <button class="button button-primary" :disabled="pending">保存显示名</button>
    </form>

    <form class="panel settings-form account-password-form" @submit.prevent="updatePassword">
      <h2 class="panel-title">修改密码</h2>
      <p class="field-hint">改密成功后当前会话保留，其他已登录会话立即失效。</p>
      <div class="field">
        <label for="account-current-password">当前密码</label>
        <input
          id="account-current-password"
          v-model="currentPassword"
          type="password"
          autocomplete="current-password"
          minlength="8"
          required
        />
      </div>
      <div class="field">
        <label for="account-new-password">新密码</label>
        <input
          id="account-new-password"
          v-model="newPassword"
          type="password"
          autocomplete="new-password"
          minlength="8"
          required
        />
      </div>
      <div class="field">
        <label for="account-password-confirmation">确认新密码</label>
        <input
          id="account-password-confirmation"
          v-model="passwordConfirmation"
          type="password"
          autocomplete="new-password"
          minlength="8"
          required
        />
      </div>
      <button type="submit" class="button button-primary" :disabled="passwordPending">
        修改密码
      </button>
      <p v-if="passwordError" class="alert" role="alert">{{ passwordError }}</p>
      <p v-if="passwordMessage" class="muted" role="status">{{ passwordMessage }}</p>
    </form>

    <section class="panel" aria-labelledby="identity-list-title">
      <h2 id="identity-list-title" class="panel-title">我的 SCM 身份</h2>
      <p class="field-hint">标签和用途帮助团队理解账号；授权始终使用 Provider、实例和数字 ID。</p>
      <ul class="record-list">
        <li v-for="identity in identities" :key="identity.id" class="record">
          <div class="record-head">
            <h3 class="record-title">{{ identity.label }}</h3>
            <span class="badge badge-info">{{ identity.usageType }}</span>
            <span class="badge badge-neutral">{{ identity.verificationStatus }}</span>
          </div>
          <p>{{ identity.provider ?? "旧身份" }} · {{ identity.instanceIdentity ?? "实例待确认" }}</p>
          <p><strong>{{ identity.externalUsername }}</strong> · 外部 ID {{ identity.externalUserId }}</p>
          <form v-if="identity.verificationStatus === 'VERIFIED'" class="identity-actions" @submit.prevent="saveIdentity(identity)">
            <div class="field"><label :for="`saved-identity-label-${identity.id}`">标签</label>
              <input :id="`saved-identity-label-${identity.id}`" v-model="identityDrafts[String(identity.id)].label" required maxlength="120" />
            </div>
            <div class="field"><label :for="`saved-identity-usage-${identity.id}`">用途</label>
              <select :id="`saved-identity-usage-${identity.id}`" v-model="identityDrafts[String(identity.id)].usageType">
                <option value="WORK">工作</option><option value="PERSONAL">个人</option>
                <option value="CLIENT">客户</option><option value="OTHER">其他</option>
              </select>
            </div>
            <button class="button button-primary">保存标签与用途</button>
            <button type="button" class="button button-quiet" @click="revoke(identity)">撤销身份</button>
          </form>
        </li>
      </ul>
    </section>

    <form class="panel settings-form" @submit.prevent="verifyIdentity">
      <h2 class="panel-title">验证新身份</h2>
      <div class="field"><label for="identity-provider">Provider</label>
        <select id="identity-provider" v-model="provider" @change="apiBase = SCM_PROVIDER_DEFAULTS[provider]">
          <option value="GITHUB">GitHub</option><option value="GITLAB">GitLab</option>
        </select>
      </div>
      <div class="field"><label for="identity-api-base">API 地址</label>
        <input id="identity-api-base" v-model="apiBase" required maxlength="512" />
      </div>
      <div class="field"><label for="identity-label">标签</label>
        <input id="identity-label" v-model="label" required maxlength="120" placeholder="例如：公司 GitHub" />
      </div>
      <div class="field"><label for="identity-usage">用途</label>
        <select id="identity-usage" v-model="usageType">
          <option value="WORK">工作</option><option value="PERSONAL">个人</option>
          <option value="CLIENT">客户</option><option value="OTHER">其他</option>
        </select>
      </div>
      <div class="field"><label for="identity-token">一次性个人 Token</label>
        <input id="identity-token" v-model="oneTimeToken" type="password" required autocomplete="off" />
        <p class="field-hint">仅用于本次 Provider 当前用户验证，提交后立即清空且不保存。</p>
        <p class="field-hint token-source">
          最小权限：<code>{{ SCM_IDENTITY_TOKEN_SCOPES[provider] }}</code> ·
          <a
            v-if="identityTokenPage"
            :href="identityTokenPage"
            target="_blank"
            rel="noreferrer"
          >{{ provider }} Token 创建页</a>
          <span v-else>
            在你的实例上打开 <code>{{ SCM_TOKEN_PAGE_PATHS[provider] }}</code> 创建 Token
          </span>
        </p>
      </div>
      <button class="button button-primary" :disabled="pending">验证并添加</button>
    </form>
  </section>
</template>

<style scoped>
.settings-form, .identity-actions { display: grid; gap: var(--fp-space-4); margin-bottom: var(--fp-space-6); }
.identity-actions { grid-template-columns: repeat(auto-fit, minmax(min(100%, 12rem), 1fr)); align-items: end; }
.settings-form .button { justify-self: start; }
/* 自建实例分支渲染的 Token 路径是长不断词串，窄屏下不断词会撑出横向滚动。 */
.token-source { word-break: break-word; }
.account-password-form .alert, .account-password-form .muted { margin: 0; }
</style>
