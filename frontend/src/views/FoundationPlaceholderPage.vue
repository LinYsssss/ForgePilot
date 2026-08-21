<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";

const route = useRoute();
const title = computed(() => String(route.meta.title ?? "ForgePilot"));
</script>

<template>
  <section class="foundation-page" aria-labelledby="foundation-title">
    <p class="eyebrow">Phase 1 · engineering foundation</p>
    <h1 id="foundation-title">{{ title }}</h1>
    <p class="lede">
      路由、请求边界与工程工具已经就位。业务能力将在对应阶段获得授权后，以纵向切片实现。
    </p>

    <div class="foundation-notice" role="note">
      <span class="notice-dot" aria-hidden="true"></span>
      <div>
        <strong>当前仅为工程底座</strong>
        <p>本页不包含模拟数据、业务操作、登录流程或审查结论。</p>
      </div>
    </div>
  </section>
</template>
