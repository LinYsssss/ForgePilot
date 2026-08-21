<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";

import { HOME_ROUTE_PATH } from "../../app/routes";
import { apiErrorMessage } from "../../lib/http";
import { register, signIn } from "./session";

const router = useRouter();

const mode = ref<"login" | "register">("login");
const username = ref("");
const password = ref("");
const pending = ref(false);
const error = ref<string | null>(null);

function switchMode(next: "login" | "register"): void {
  mode.value = next;
  error.value = null;
}

async function submit(): Promise<void> {
  pending.value = true;
  error.value = null;
  try {
    if (mode.value === "register") {
      await register(username.value, password.value);
    }
    await signIn(username.value, password.value);
    await router.push(HOME_ROUTE_PATH);
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  } finally {
    pending.value = false;
  }
}
</script>

<template>
  <section class="login-page" aria-labelledby="login-title">
    <div class="page-head">
      <p class="eyebrow">ForgePilot</p>
      <h1 id="login-title">{{ mode === "login" ? "登录" : "注册并登录" }}</h1>
    </div>

    <form class="panel login-form" @submit.prevent="submit">
      <p v-if="error" class="alert" role="alert">{{ error }}</p>

      <div class="field">
        <label for="login-username">用户名</label>
        <input
          id="login-username"
          v-model="username"
          name="username"
          autocomplete="username"
          required
          maxlength="64"
        />
      </div>

      <div class="field">
        <label for="login-password">口令</label>
        <input
          id="login-password"
          v-model="password"
          name="password"
          type="password"
          :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
          required
          minlength="8"
        />
        <p class="field-hint">至少 8 个字符。</p>
      </div>

      <div class="form-actions">
        <button type="submit" class="button button-primary" :disabled="pending">
          {{ mode === "login" ? "登录" : "注册并登录" }}
        </button>
        <button
          v-if="mode === 'login'"
          type="button"
          class="button button-quiet"
          @click="switchMode('register')"
        >
          没有账号？去注册
        </button>
        <button
          v-else
          type="button"
          class="button button-quiet"
          @click="switchMode('login')"
        >
          已有账号？去登录
        </button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.login-page {
  max-width: 28rem;
  margin: 0 auto;
}

.login-form {
  display: grid;
  gap: var(--fp-space-4);
}
</style>
