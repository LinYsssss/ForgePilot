<script setup lang="ts">
import { RouterLink, RouterView, useRouter } from "vue-router";

import { HOME_ROUTE_PATH, LOGIN_ROUTE_PATH, TOP_LEVEL_NAVIGATION } from "../app/routes";
import { signOut, useSession } from "../features/auth/session";

const router = useRouter();
const { account } = useSession();

async function logout(): Promise<void> {
  await signOut();
  await router.replace(LOGIN_ROUTE_PATH);
}
</script>

<template>
  <a class="skip-link" href="#app-main">跳到主要内容</a>
  <div class="app-shell">
    <header class="app-header">
      <RouterLink class="brand" :to="HOME_ROUTE_PATH" aria-label="ForgePilot 首页">
        <span class="brand-mark" aria-hidden="true">FP</span>
        <span class="brand-copy">
          <strong>ForgePilot</strong>
          <small>Requirement-driven review console</small>
        </span>
      </RouterLink>

      <nav v-if="account" aria-label="主导航">
        <RouterLink
          v-for="item in TOP_LEVEL_NAVIGATION"
          :key="item.to"
          :to="item.to"
          class="nav-link"
        >
          {{ item.label }}
        </RouterLink>
      </nav>

      <div v-if="account" class="session-area">
        <span class="session-user">{{ account.username }}</span>
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
