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
    <div class="login-stage">
      <div class="login-story" aria-labelledby="login-story-title">
        <p class="eyebrow">ForgePilot · Review Console</p>
        <p id="login-story-title" class="login-story-title">把需求、项目规范与代码变更放进同一条证据链。</p>
        <p class="login-story-copy">
          ForgePilot 围绕需求驱动的 Pull Request 审查构建，让每条 Finding
          都能回到验收标准、项目知识和具体代码证据，最终由团队成员完成判断。
        </p>
        <ol class="login-causal-chain" aria-label="ForgePilot 核心流程">
          <li>
            <span class="chain-index">01</span>
            <span><strong>需求契约</strong><small>Requirement 与 AC 定义应该交付什么</small></span>
          </li>
          <li>
            <span class="chain-index">02</span>
            <span><strong>上下文审查</strong><small>项目规范与 PR Diff 形成可核验证据</small></span>
          </li>
          <li>
            <span class="chain-index">03</span>
            <span><strong>人工闭环</strong><small>团队确认 Finding 并做出终局决定</small></span>
          </li>
        </ol>
      </div>

      <div class="login-access">
        <div class="login-heading">
          <p class="eyebrow">Secure session</p>
          <h1 id="login-title">{{ mode === "login" ? "登录" : "注册并登录" }}</h1>
          <p class="muted">
            {{
              mode === "login"
                ? "使用本地账户进入审查控制台。"
                : "创建本地演示账户并立即进入控制台。"
            }}
          </p>
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
              placeholder="输入用户名"
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
              placeholder="至少 8 个字符"
              required
              minlength="8"
            />
            <p class="field-hint">口令只用于当前本地账户会话。</p>
          </div>

          <div class="form-actions">
            <button type="submit" class="button button-primary login-submit" :disabled="pending">
              {{
                pending
                  ? "正在建立会话…"
                  : mode === "login"
                    ? "进入 ForgePilot"
                    : "创建账户并进入"
              }}
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
      </div>
    </div>
  </section>
</template>

<style scoped>
.login-page {
  display: grid;
  min-height: calc(100vh - 12rem);
  place-items: center;
}

.login-stage {
  display: grid;
  width: min(68rem, 100%);
  overflow: hidden;
  border: 0.0625rem solid var(--fp-color-border);
  border-radius: var(--fp-radius-xl);
  background: var(--fp-color-surface-glass);
  box-shadow: var(--fp-shadow-elevated), var(--fp-shadow-accent);
  grid-template-columns: minmax(0, 1.12fr) minmax(22rem, 0.88fr);
  backdrop-filter: blur(1.25rem);
}

.login-story,
.login-access {
  padding: clamp(var(--fp-space-8), 6vw, var(--fp-space-16));
}

.login-story {
  position: relative;
  overflow: hidden;
  border-right: 0.0625rem solid var(--fp-color-border);
  background: var(--fp-gradient-panel);
}

.login-story::after {
  position: absolute;
  right: -8rem;
  bottom: -9rem;
  width: 24rem;
  height: 24rem;
  border-radius: 50%;
  background: var(--fp-color-secondary-soft);
  box-shadow: 0 0 6rem var(--fp-color-accent-glow);
  content: "";
  pointer-events: none;
}

.login-story > * {
  position: relative;
  z-index: 1;
}

.login-story-title {
  max-width: 34rem;
  margin: 0;
  font-size: clamp(1.75rem, 4vw, 3rem);
  font-weight: 750;
  line-height: 1.2;
  letter-spacing: -0.04em;
}

.login-story-copy {
  max-width: 34rem;
  margin: var(--fp-space-6) 0 0;
  color: var(--fp-color-text-muted);
  font-size: 0.9375rem;
  line-height: 1.8;
}

.login-causal-chain {
  display: grid;
  gap: var(--fp-space-3);
  margin: var(--fp-space-10) 0 0;
  padding: 0;
  list-style: none;
}

.login-causal-chain li {
  display: flex;
  align-items: center;
  gap: var(--fp-space-4);
  padding: var(--fp-space-4);
  border: 0.0625rem solid var(--fp-color-border);
  border-radius: var(--fp-radius-md);
  background: var(--fp-color-surface-glass);
}

.chain-index {
  color: var(--fp-color-accent);
  font: 800 0.6875rem/1 var(--fp-font-mono);
}

.login-causal-chain strong,
.login-causal-chain small {
  display: block;
}

.login-causal-chain small {
  margin-top: var(--fp-space-1);
  color: var(--fp-color-text-subtle);
  font-size: 0.75rem;
}

.login-access {
  display: flex;
  justify-content: center;
  flex-direction: column;
  background: var(--fp-color-surface-glass);
}

.login-heading {
  margin-bottom: var(--fp-space-6);
}

.login-heading h1 {
  font-size: clamp(1.75rem, 4vw, 2.25rem);
}

.login-form {
  display: grid;
  gap: var(--fp-space-5);
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.login-form::after {
  display: none;
}

.login-submit {
  min-width: 11rem;
}

@media (max-width: 64rem) {
  .login-stage {
    grid-template-columns: 1fr;
  }

  .login-story {
    border-right: 0;
    border-bottom: 0.0625rem solid var(--fp-color-border);
  }
}

@media (max-width: 42rem) {
  .login-page {
    min-height: auto;
  }

  .login-stage {
    border-radius: var(--fp-radius-md);
  }

  .login-story,
  .login-access {
    padding: var(--fp-space-6);
  }

  .login-story-title {
    font-size: 1.75rem;
  }

  .login-causal-chain {
    margin-top: var(--fp-space-6);
  }
}
</style>
