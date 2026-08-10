<template>
  <div class="login-page">
    <main class="login-card">
      <header class="login-brand">
        <span class="login-logo" aria-hidden="true">R</span>
        <h1>RepoSage</h1>
      </header>
      <p class="login-sub">AI 代码仓库智能审查平台</p>
      <el-form label-position="top" size="large" @submit.prevent>
        <el-form-item label="用户名">
          <el-input v-model="auth.username" placeholder="请输入用户名" autocomplete="username" @keyup.enter="login" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="auth.password" type="password" placeholder="至少 6 位" autocomplete="current-password" @keyup.enter="login" />
        </el-form-item>
        <el-button class="login-submit" type="primary" :loading="busy.auth" @click="login">登录</el-button>
      </el-form>
      <p class="login-hint">账号由管理员分配，如需账号请联系管理员。</p>
    </main>
    <transition name="t"><div v-if="toast.text" class="toast" :class="toast.type" role="status">{{ toast.text }}</div></transition>
  </div>
</template>

<script setup>
import { reactive } from 'vue'
import { api, initCsrf } from '../api/client.js'
import { useBusy } from '../composables/useBusy.js'
import { useSession } from '../composables/useSession.js'
import { useToast } from '../composables/useToast.js'

const emit = defineEmits(['authenticated'])
const { busy } = useBusy()
const { authenticated } = useSession()
const { toast, toastMsg } = useToast()
const auth = reactive({ username: '', password: '' })

async function login() {
  busy.auth = true
  try {
    await api('/auth/login', { method: 'POST', body: JSON.stringify(auth) })
    // 登录会轮换 CSRF token,且新 cookie 延迟到"下一次渲染 token 的请求"才发;
    // 必须立刻重新引导,否则登录后的第一个写请求会因缺 token 被拒并触发全局登出。
    await initCsrf()
    authenticated.value = true
    emit('authenticated')
  } catch (error) {
    toastMsg(error?.message || '登录失败', 'error')
  } finally { busy.auth = false }
}
</script>

<style scoped>
/* Precision Workbench 登录面:卡片手铸(纯布局,tokens 直供,比 el-card
   少一层默认内边距/描边语义要打的架);表单交互全交给 Element。 */
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: var(--sp-5);
  font-family: var(--rs-font-body);
  color: var(--rs-text);
  /* 顶部一层极淡的品牌 teal 晕染打底:静态、无动画、无模糊光斑 */
  background:
    radial-gradient(880px 460px at 50% -140px, var(--el-color-primary-light-9), transparent 68%),
    var(--rs-bg);
}

.login-card {
  position: relative;
  overflow: hidden;
  width: 100%;
  max-width: 420px;
  padding: var(--sp-8);
  background: var(--rs-surface);
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-base);
  box-shadow: var(--rs-shadow-md);
  animation: login-rise var(--rs-t-slow) var(--rs-ease-out) both;
}

/* 顶缘 3px 品牌线:precision 点睛,无渐变无光效 */
.login-card::before {
  content: "";
  position: absolute;
  inset: 0 0 auto 0;
  height: 3px;
  background: var(--rs-primary);
}

/* 品牌块为本页 scoped 重铸(AppShell 的全局 .brand/.brand-logo 不共用) */
.login-brand {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--sp-3);
  margin-top: var(--sp-2);
}
.login-logo {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  background: var(--rs-primary);
  color: var(--rs-surface);
  border-radius: var(--rs-radius-base);
  font-size: var(--rs-fs-lg);
  font-weight: 800;
}
.login-brand h1 {
  margin: 0;
  font-family: var(--rs-font-body);
  font-size: var(--rs-fs-xl);
  font-weight: 800;
  letter-spacing: -0.01em;
  color: var(--rs-text);
}

.login-sub {
  margin: var(--sp-2) 0 var(--sp-6);
  text-align: center;
  font-size: var(--rs-fs-sm);
  color: var(--rs-text-dim);
}

.login-submit {
  width: 100%;
  margin-top: var(--sp-2);
  font-weight: 600;
}

.login-hint {
  margin: var(--sp-4) 0 0;
  text-align: center;
  font-size: var(--rs-fs-sm);
  line-height: 1.5;
  color: var(--rs-text-dim);
}

@keyframes login-rise {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: none; }
}
@media (prefers-reduced-motion: reduce) {
  .login-card { animation: none; }
}
</style>
