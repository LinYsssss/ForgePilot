<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
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
import { signOut, useSession } from "../features/auth/session";

const router = useRouter();
const route = useRoute();
const { account } = useSession();

/** 头像取显示名首字:中文名首字比拉丁缩写更好认。 */
const accountInitial = computed(() => (account.value?.displayName ?? "").trim().charAt(0));

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

/*
 * 原生 <details> 不会因为点击外部而关闭，账户弹层曾因此关不掉。这里补上外部
 * 点击、Esc 和路由变化三条关闭路径，仍然只操作 details.open，不引入第二套
 * 弹层运行时或焦点陷阱。
 */
const accountMenu = ref<HTMLDetailsElement | null>(null);

function closeAccountMenu(): void {
  const menu = accountMenu.value;
  if (menu !== null) {
    menu.open = false;
  }
}

function closeOnOutsidePointer(event: PointerEvent): void {
  const menu = accountMenu.value;
  if (menu !== null && menu.open && !menu.contains(event.target as Node)) {
    menu.open = false;
  }
}

function closeOnEscape(event: KeyboardEvent): void {
  const menu = accountMenu.value;
  if (event.key === "Escape" && menu !== null && menu.open) {
    menu.open = false;
    // 焦点不能留在刚消失的弹层里，否则键盘用户失去位置。
    menu.querySelector("summary")?.focus();
  }
}

onMounted(() => {
  document.addEventListener("pointerdown", closeOnOutsidePointer);
  document.addEventListener("keydown", closeOnEscape);
});

onBeforeUnmount(() => {
  document.removeEventListener("pointerdown", closeOnOutsidePointer);
  document.removeEventListener("keydown", closeOnEscape);
});

watch(() => route.fullPath, closeAccountMenu);

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
        <details ref="accountMenu" class="account-menu">
          <summary class="button button-inverse session-user">
            <span class="session-avatar" aria-hidden="true">{{ accountInitial }}</span>
            <span class="session-user-name">{{ account.displayName }}</span>
            <span class="session-caret" aria-hidden="true">▾</span>
          </summary>
          <div class="account-popover">
            <h2 class="panel-title">账户</h2>
            <p class="field-hint">{{ account.displayName }} · @{{ account.username }} · ID {{ account.id }}</p>
            <div class="account-popover-actions">
              <RouterLink class="button button-quiet" :to="ACCOUNT_ROUTE_PATH">账户设置</RouterLink>
              <button type="button" class="button button-danger" @click="logout">退出登录</button>
            </div>
          </div>
        </details>
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

.session-user {
  gap: var(--fp-space-2);
  padding-inline: var(--fp-space-3);
}

/* base.css 的在线小圆点属于纯文本形态;带上头像后它就成了两个身份标记。 */
.session-user::before {
  content: none;
}

.session-avatar {
  display: inline-grid;
  place-items: center;
  width: 1.75rem;
  height: 1.75rem;
  border-radius: 50%;
  background: var(--fp-gradient-brand);
  color: var(--fp-color-text-inverse);
  font: 800 0.75rem/1 var(--fp-font-sans);
}

.session-user-name {
  max-width: 8rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-caret {
  color: var(--fp-color-text-muted);
  font-size: 0.625rem;
}

.account-popover-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fp-space-3);
  margin-top: var(--fp-space-4);
}

.account-popover .eyebrow {
  margin-bottom: var(--fp-space-2);
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
