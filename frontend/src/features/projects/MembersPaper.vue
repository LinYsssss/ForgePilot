<template>
  <section class="ink-panel members-panel ink-reveal">
    <header class="panel-head">
      <div>
        <span class="ink-eyebrow">成员管理</span>
        <h2>{{ projectName }} · {{ members.length }} 人</h2>
      </div>
    </header>

    <form v-if="canManage" class="member-add" @submit.prevent="submitAdd">
      <label>
        <span>用户名</span>
        <input v-model="addForm.username" class="ink-field" placeholder="按注册用户名添加" />
      </label>
      <label>
        <span>角色</span>
        <select v-model="addForm.role" class="ink-field">
          <option value="DEVELOPER">开发人员</option>
          <option value="REVIEWER">审查人员</option>
        </select>
      </label>
      <button class="ink-primary-button" type="submit">添加成员</button>
    </form>

    <p v-if="loading" class="member-hint">正在取名册…</p>
    <ul v-else class="member-list">
      <li v-for="member in members" :key="member.userId" class="member-row">
        <div class="member-id">
          <span class="member-name">{{ member.nickname || member.username || ('用户#' + member.userId) }}</span>
          <code class="member-username">{{ member.username }}</code>
        </div>
        <span class="member-role" :data-role="member.role">{{ roleLabel(member.role) }}</span>
        <div v-if="canManage" class="member-actions">
          <template v-if="!member.owner">
            <select
              class="ink-field role-select"
              :value="member.role"
              :aria-label="`调整 ${member.username} 的角色`"
              @change="$emit('change-role', member, $event.target.value)"
            >
              <option value="DEVELOPER">开发人员</option>
              <option value="REVIEWER">审查人员</option>
            </select>
            <button class="ink-text-button" @click="$emit('transfer', member)">移交负责人</button>
            <button class="ink-text-button is-danger" @click="$emit('remove', member)">移除</button>
          </template>
          <span v-else class="member-owner-note">当前负责人</span>
        </div>
      </li>
    </ul>
  </section>
</template>

<script setup>
import { reactive } from 'vue'

// 成员名册(P1a)。展示 + 事件上抛,与 ProjectsPaper 同一姿态:数据与动作都由页面注入,
// 组件不直接碰 api。canManage=false(非 LEADER)时整段管理入口不渲染——后端仍会二次裁决。
const props = defineProps({
  projectName: { type: String, default: '' },
  members: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  canManage: { type: Boolean, default: false },
})
const emit = defineEmits(['add', 'change-role', 'transfer', 'remove'])

const addForm = reactive({ username: '', role: 'DEVELOPER' })

function submitAdd() {
  emit('add', { ...addForm })
  addForm.username = ''
}

function roleLabel(role) {
  return { LEADER: '负责人', DEVELOPER: '开发人员', REVIEWER: '审查人员' }[role] || role
}
</script>

<style scoped>
.ink-panel {
  padding: var(--ink-sp-24);
  background: var(--ink-wash-panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--ink-radius-paper);
  box-shadow: var(--ink-shadow-panel);
}

.panel-head h2 { margin: var(--ink-sp-4) 0 0; font-family: var(--ink-font-display); font-size: var(--ink-fs-20); color: var(--ink-strong); }

.member-add { display: grid; grid-template-columns: minmax(0, 2fr) minmax(0, 1fr) auto; gap: var(--ink-sp-12); align-items: end; margin-top: var(--ink-sp-16); }
.member-add label { display: grid; gap: var(--ink-sp-4); }
.member-add label span { color: var(--ink-muted); font-size: var(--ink-fs-13); }

.ink-field {
  width: 100%;
  padding: var(--ink-sp-8) var(--ink-sp-12);
  background: var(--ink-wash-field);
  border: 1px solid var(--line-soft);
  border-radius: var(--ink-radius-control);
  color: var(--ink-default);
  font: inherit;
}
.ink-field:focus-visible { outline: 2px solid var(--mineral-cyan); outline-offset: 1px; border-color: var(--line-strong); }

.member-hint { margin: var(--ink-sp-16) 0 0; color: var(--ink-muted); }

.member-list { list-style: none; margin: var(--ink-sp-16) 0 0; padding: 0; display: grid; gap: var(--ink-sp-8); }
.member-row {
  display: flex;
  align-items: center;
  gap: var(--ink-sp-12);
  padding: var(--ink-sp-12) var(--ink-sp-16);
  background: var(--surface-paper);
  border: 1px solid var(--line-soft);
  border-radius: var(--ink-radius-control);
  flex-wrap: wrap;
}
.member-id { display: grid; gap: 2px; min-width: 0; }
.member-name { color: var(--ink-strong); font-size: var(--ink-fs-14); }
.member-username { color: var(--ink-muted); font-family: var(--ink-font-code); font-size: var(--ink-fs-12); }

.member-role { padding: 2px var(--ink-sp-8); border: 1px solid var(--line-soft); border-radius: 999px; color: var(--ink-muted); font-size: var(--ink-fs-12); }
.member-role[data-role='LEADER'] { color: var(--mineral-cyan); border-color: var(--mineral-cyan); }

.member-actions { display: flex; align-items: center; gap: var(--ink-sp-8); margin-left: auto; flex-wrap: wrap; }
.role-select { width: auto; padding: var(--ink-sp-4) var(--ink-sp-8); }
.member-owner-note { margin-left: auto; color: var(--ink-muted); font-size: var(--ink-fs-12); }
.ink-text-button.is-danger { color: var(--cinnabar); }

@media (max-width: 767px) {
  .member-add { grid-template-columns: 1fr; }
}
</style>
