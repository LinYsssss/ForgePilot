<script setup lang="ts">
import { computed, ref } from "vue";
import {
  RouterLink,
  RouterView,
  useRoute,
  useRouter,
  type RouteLocationRaw,
} from "vue-router";

import {
  HOME_ROUTE_PATH,
  ACCOUNT_ROUTE_PATH,
  LOGIN_ROUTE_PATH,
  parseId,
  PROJECT_QUERY_KEY,
  TOP_LEVEL_NAVIGATION,
} from "../app/routes";
import { changePassword, signOut, useSession } from "../features/auth/session";
import { apiErrorMessage } from "../lib/http";

const router = useRouter();
const route = useRoute();
const { account } = useSession();

const currentProjectId = computed(
  () =>
    parseId(route.query[PROJECT_QUERY_KEY]) ??
    (route.path.startsWith("/projects/") ? parseId(route.params.id) : null),
);

function navigationTarget(path: string): RouteLocationRaw {
  const project = currentProjectId.value;
  return project !== null && ["/workspace", "/requirements", "/knowledge", "/repositories", "/reviews"].includes(path)
    ? { path, query: { [PROJECT_QUERY_KEY]: String(project) } }
    : path;
}

const currentPassword = ref("");
const newPassword = ref("");
const passwordConfirmation = ref("");
const passwordPending = ref(false);
const passwordError = ref<string | null>(null);
const passwordMessage = ref<string | null>(null);

function accountMenuToggled(event: Event): void {
  const target = event.currentTarget;
  if (!(target instanceof HTMLDetailsElement) || target.open) {
    return;
  }
  currentPassword.value = "";
  newPassword.value = "";
  passwordConfirmation.value = "";
  passwordError.value = null;
  passwordMessage.value = null;
}

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

async function logout(): Promise<void> {
  await signOut();
  await router.replace(LOGIN_ROUTE_PATH);
}
</script>

<template>
  <a class="skip-link" href="#app-main">跳到主要内容</a>
  <div :class="['app-shell', { 'app-shell-signed-in': account }]">
    <header v-if="account" class="app-header">
      <RouterLink class="brand" :to="HOME_ROUTE_PATH" aria-label="ForgePilot 工作台">
        <img class="brand-lockup" src="/brand/logo-lockup.png" alt="ForgePilot" />
        <span class="brand-copy"><small>Requirement-driven AI review</small></span>
      </RouterLink>
      <nav class="primary-navigation" aria-label="主导航">
        <RouterLink
          v-for="item in TOP_LEVEL_NAVIGATION"
          :key="item.to"
          :to="navigationTarget(item.to)"
          class="nav-link"
        >
          {{ item.label }}
        </RouterLink>
      </nav>

      <div class="session-area">
        <details class="account-menu" @toggle="accountMenuToggled">
          <summary class="button button-inverse session-user">{{ account.displayName }}</summary>
          <div class="account-popover">
            <p class="eyebrow">Account security</p>
            <h2 class="panel-title">账户与安全</h2>
            <p class="field-hint">{{ account.displayName }} · @{{ account.username }} · ID {{ account.id }}</p>
            <RouterLink class="button button-quiet" :to="ACCOUNT_ROUTE_PATH">管理资料与 SCM 身份</RouterLink>
            <form class="account-password-form" @submit.prevent="updatePassword">
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
          </div>
        </details>
        <button type="button" class="button button-inverse" @click="logout">退出登录</button>
      </div>
    </header>

    <main id="app-main" tabindex="-1">
      <RouterView v-slot="{ Component, route }">
        <Transition name="cyber-route" mode="out-in">
          <component :is="Component" :key="route.fullPath" />
        </Transition>
      </RouterView>
    </main>
  </div>
</template>

<style scoped>
.account-menu {
  position: relative;
}

.account-menu > summary {
  list-style: none;
  cursor: pointer;
}

.account-menu > summary::-webkit-details-marker {
  display: none;
}

.account-popover {
  position: absolute;
  top: calc(100% + var(--fp-space-3));
  right: 0;
  width: min(24rem, calc(100vw - var(--fp-space-8)));
  padding: var(--fp-space-5);
  border: 0.0625rem solid var(--fp-color-border-accent);
  border-radius: var(--fp-radius-lg);
  background: var(--fp-color-surface-overlay);
  box-shadow: var(--fp-shadow-elevated), var(--fp-shadow-accent);
  backdrop-filter: blur(1.25rem) saturate(130%);
}

.account-popover .eyebrow {
  margin-bottom: var(--fp-space-2);
}

.account-password-form {
  display: grid;
  gap: var(--fp-space-4);
}

.account-password-form .alert,
.account-password-form .muted {
  margin: 0;
}

@media (max-width: 42rem) {
  .account-popover {
    position: fixed;
    top: 8.25rem;
    right: var(--fp-space-4);
    left: var(--fp-space-4);
    width: auto;
  }
}
</style>
