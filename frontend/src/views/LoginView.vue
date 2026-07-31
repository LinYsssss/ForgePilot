<template>
  <div class="auth-wrap">
    <div class="auth-card">
      <div class="brand">
        <div class="brand-logo">R</div>
        <div><h1>RepoSage</h1></div>
      </div>
      <p class="auth-sub">AI 代码仓库智能审查平台</p>
      <div class="grid">
        <label class="field">用户名
          <input v-model="auth.username" placeholder="请输入用户名" autocomplete="username" @keyup.enter="login" />
        </label>
        <label class="field">密码
          <input v-model="auth.password" type="password" placeholder="至少 6 位" autocomplete="current-password" @keyup.enter="login" />
        </label>
      </div>
      <div class="actions">
        <button @click="login" :disabled="busy.auth">
          <span v-if="busy.auth" class="spinner"></span>登录
        </button>
      </div>
      <p class="hint">账号由管理员分配，如需账号请联系管理员。</p>
    </div>
    <transition name="t"><div v-if="toast.text" class="toast" :class="toast.type" role="status">{{ toast.text }}</div></transition>
  </div>
</template>

<script setup>
import { reactive } from 'vue'
import { api } from '../api/client.js'
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
    authenticated.value = true
    emit('authenticated')
  } catch (error) {
    toastMsg(error?.message || '登录失败', 'error')
  } finally { busy.auth = false }
}
</script>
