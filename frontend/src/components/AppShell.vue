<script setup lang="ts">
import { RouterLink, RouterView } from "vue-router";

import { HOME_ROUTE_PATH, TOP_LEVEL_NAVIGATION } from "../app/routes";
</script>

<template>
  <a class="skip-link" href="#app-main">跳到主要内容</a>
  <div class="app-shell">
    <header class="app-header">
      <RouterLink class="brand" :to="HOME_ROUTE_PATH" aria-label="ForgePilot 首页">
        <span class="brand-mark" aria-hidden="true">FP</span>
        <span>
          <strong>ForgePilot</strong>
          <small>Requirement-driven review</small>
        </span>
      </RouterLink>

      <nav aria-label="主导航">
        <RouterLink
          v-for="item in TOP_LEVEL_NAVIGATION"
          :key="item.to"
          :to="item.to"
          class="nav-link"
        >
          {{ item.label }}
        </RouterLink>
      </nav>
    </header>

    <main id="app-main" tabindex="-1">
      <RouterView />
    </main>
  </div>
</template>
