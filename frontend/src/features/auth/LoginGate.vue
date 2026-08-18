<template>
  <div class="ink-stage ink-login" :class="{ 'ink-static': staticMode }">
    <InkAmbientScene variant="login" />

    <div class="login-shell">
      <section class="login-story" aria-labelledby="ink-login-story-title">
        <div class="ink-brand">
          <span class="ink-brand-mark" aria-hidden="true">睿</span>
          <span class="brand-text"><strong>{{ PRODUCT_NAME }}</strong><small>{{ PRODUCT_TAGLINE }}</small></span>
        </div>
        <div class="login-story-copy">
          <div class="taiji-anchor"><TaijiAmbientMark variant="anchor" /></div>
          <span class="ink-eyebrow">AI Code Review Gatekeeper</span>
          <h1 id="ink-login-story-title">入境观心<br />落墨有据</h1>
          <p>让每一条风险结论回到可验证证据。太极水墨随环境缓慢流动，真正的审查信息始终清晰、稳定、可追溯。</p>
        </div>
        <div class="login-story-note">"静处见风险，动处循证据。"<br />{{ PRODUCT_NAME }} 将 Agent 过程、Finding 与 Patch 审批收进同一案卷。</div>
      </section>

      <section class="login-panel-wrap">
        <div class="login-panel">
          <span class="ink-eyebrow">书院门禁</span>
          <h2>登录审查工作台</h2>
          <p class="panel-sub">使用组织分配的账号进入墨境工作台。</p>
          <div v-if="errorText" class="login-error" role="alert" aria-live="assertive">{{ errorText }}</div>
          <form class="login-form" novalidate @submit.prevent="submit">
            <label class="login-field">
              <span>组织账号</span>
              <input
                ref="usernameEl"
                v-model="form.username"
                name="username"
                type="text"
                autocomplete="username"
                placeholder="请输入用户名"
                :aria-invalid="invalid.username ? 'true' : undefined"
                required
              />
            </label>
            <label class="login-field">
              <span>密码</span>
              <input
                ref="passwordEl"
                v-model="form.password"
                name="password"
                type="password"
                autocomplete="current-password"
                placeholder="请输入密码"
                :aria-invalid="invalid.password ? 'true' : undefined"
                required
              />
            </label>
            <button class="ink-primary-button login-submit" type="submit" :disabled="busy.auth" :aria-busy="String(!!busy.auth)">
              {{ busy.auth ? '正在验明身份…' : '入境审查' }}
            </button>
          </form>
          <div class="login-foot">
            <button class="ink-text-button" type="button" :aria-pressed="String(staticMode)" @click="toggleStaticMode()">
              {{ staticMode ? '启用水墨' : '静态墨境' }}
            </button>
            <p>账号由管理员分配，如需账号请联系管理员。</p>
          </div>
        </div>
      </section>
    </div>

    <transition name="t">
      <div v-if="toast.text" class="ink-toast" :class="toast.type" role="status">{{ toast.text }}</div>
    </transition>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import InkAmbientScene from '../../shared/motion/InkAmbientScene.vue'
import TaijiAmbientMark from '../../shared/motion/TaijiAmbientMark.vue'
import { api, initCsrf } from '../../api/client.js'
import { useBusy } from '../../composables/useBusy.js'
import { useSession } from '../../composables/useSession.js'
import { useToast } from '../../composables/useToast.js'
import { useMotionPolicy } from '../../shared/motion/useMotionPolicy.js'
import { bindPointerField } from '../../shared/motion/usePointerField.js'
import { PRODUCT_NAME, PRODUCT_TAGLINE } from '../../shared/brand.js'

// 墨境登录门禁(合同 §5 LoginGate):表单处于稳定近实色纸面,太极/云雾/
// 墨粒只在背后;错误走朱砂细框 + aria-live,不闪烁。
// 认证语义与旧 LoginView 完全一致:POST /auth/login → 立刻重引导 CSRF
// (登录轮换令牌,不重引导则登录后首个写请求会被拒)→ authenticated=true。
// 会话凭据只存在 HttpOnly Cookie,本组件不触碰 Web Storage。
const emit = defineEmits(['authenticated'])

const { busy } = useBusy()
const { authenticated } = useSession()
const { toast } = useToast()
const { staticMode, toggleStaticMode, bindMotionPolicy } = useMotionPolicy()

const form = reactive({ username: '', password: '' })
const invalid = reactive({ username: false, password: false })
const errorText = ref('')
const usernameEl = ref(null)
const passwordEl = ref(null)

async function submit() {
  if (busy.auth) return
  invalid.username = !form.username.trim()
  invalid.password = !form.password
  if (invalid.username || invalid.password) {
    errorText.value = '账号或密码不能为空，请补全后重试。'
    ;(invalid.username ? usernameEl : passwordEl).value?.focus()
    return
  }
  errorText.value = ''
  busy.auth = true
  try {
    await api('/auth/login', { method: 'POST', body: JSON.stringify({ username: form.username, password: form.password }) })
    await initCsrf()
    authenticated.value = true
    emit('authenticated')
  } catch (error) {
    errorText.value = error?.message || '登录失败'
  } finally {
    busy.auth = false
  }
}

let unbindMotion = null
let unbindPointer = null
onMounted(() => {
  unbindMotion = bindMotionPolicy()
  unbindPointer = bindPointerField()
})
onBeforeUnmount(() => {
  unbindMotion?.()
  unbindPointer?.()
})
</script>

<style scoped>
.login-shell {
  position: relative;
  z-index: 1;
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(320px, 1.1fr) minmax(360px, 0.9fr);
  align-items: stretch;
}

/* ---- 左侧叙事面 ---- */
.login-story {
  min-height: 100vh;
  padding: clamp(40px, 7vw, 100px);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}
.ink-brand { display: flex; align-items: center; gap: var(--ink-sp-12); width: fit-content; color: var(--ink-strong); }
.ink-brand-mark {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  color: var(--cinnabar);
  border: 2px solid currentColor;
  border-radius: var(--ink-radius-paper);
  font-family: var(--ink-font-display);
  font-size: var(--ink-fs-16);
  font-weight: 700;
  transform: rotate(-2deg);
}
.brand-text strong { display: block; font-family: var(--ink-font-display); font-size: var(--ink-fs-16); letter-spacing: 0.02em; }
.brand-text small { display: block; margin-top: 1px; color: var(--ink-muted); font-size: var(--ink-fs-12); letter-spacing: 0.16em; }

.login-story-copy { max-width: 610px; padding: 50px 0; }
.taiji-anchor { width: 92px; height: 92px; margin-bottom: var(--ink-sp-24); opacity: 0.6; }
.login-story-copy h1 {
  margin: var(--ink-sp-12) 0 var(--ink-sp-16);
  font-family: var(--ink-font-display);
  font-size: clamp(36px, 5vw, 64px);
  font-weight: 600;
  line-height: 1.15;
  letter-spacing: 0.08em;
}
.login-story-copy p { max-width: 520px; margin: 0; color: var(--ink-muted); font-size: var(--ink-fs-15); line-height: 1.9; }
.login-story-note {
  max-width: 450px;
  padding-left: var(--ink-sp-16);
  color: var(--ink-muted);
  border-left: 2px solid var(--cinnabar);
  font-family: var(--ink-font-display);
  font-size: var(--ink-fs-13);
  line-height: 1.8;
}

/* ---- 右侧稳定门禁面 ---- */
.login-panel-wrap {
  display: grid;
  place-items: center;
  padding: 44px;
  background: var(--ink-wash-login-side);
  backdrop-filter: blur(12px);
  border-left: 1px solid var(--line-strong);
}
.login-panel {
  width: min(420px, 100%);
  padding: clamp(26px, 4vw, 42px);
  background: var(--ink-wash-login-panel);
  border: 1px solid var(--line-strong);
  border-radius: var(--ink-radius-paper);
  box-shadow: var(--ink-shadow-layer);
}
.login-panel h2 { margin: 6px 0 8px; font-family: var(--ink-font-display); font-size: var(--ink-fs-20); font-weight: 600; }
.panel-sub { margin: 0 0 var(--ink-sp-24); color: var(--ink-muted); font-size: var(--ink-fs-13); line-height: 1.6; }

.login-error {
  margin-bottom: var(--ink-sp-16);
  padding: var(--ink-sp-8) var(--ink-sp-12);
  color: var(--cinnabar-hover);
  background: var(--cinnabar-soft);
  border-left: 3px solid var(--cinnabar);
  font-size: var(--ink-fs-13);
}

.login-form { display: grid; gap: var(--ink-sp-16); }
.login-field { display: grid; gap: 7px; }
.login-field > span { color: var(--ink-strong); font-size: var(--ink-fs-13); font-weight: 700; }
.login-field input {
  width: 100%;
  min-height: 44px;
  padding: 0 var(--ink-sp-12);
  color: var(--ink-strong);
  background: var(--surface-paper);
  border: 1px solid var(--line-strong);
  border-radius: var(--ink-radius-control);
}
.login-field input::placeholder { color: var(--ink-muted); }
.login-field input[aria-invalid='true'] { border-color: var(--cinnabar); }
.login-submit { width: 100%; min-height: 46px; }

.login-foot { margin-top: var(--ink-sp-24); text-align: center; }
.login-foot p { margin: var(--ink-sp-8) 0 0; color: var(--ink-muted); font-size: var(--ink-fs-12); line-height: 1.5; }

/* ---- 单列(≤840,与已批准原型一致) ---- */
@media (max-width: 840px) {
  .login-shell { grid-template-columns: 1fr; }
  .login-story { min-height: auto; padding: 28px 24px 10px; }
  .login-story-copy { padding: 48px 0 24px; }
  .taiji-anchor { width: 62px; height: 62px; }
  .login-story-copy h1 { font-size: 36px; }
  .login-story-note { display: none; }
  .login-panel-wrap { padding: 24px 18px 42px; border-left: 0; background: transparent; }
}
</style>
