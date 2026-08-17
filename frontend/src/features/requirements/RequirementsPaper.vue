<template>
  <div class="req-layout">
    <section class="ink-panel req-list ink-reveal">
      <header class="panel-head">
        <div>
          <span class="ink-eyebrow">研发任务</span>
          <h1>需求 · {{ total }} 条</h1>
        </div>
        <div class="head-actions">
          <select class="ink-field filter-select" :value="filter" @change="$emit('filter', $event.target.value)">
            <option value="">全部状态</option>
            <option v-for="(label, key) in statusLabels" :key="key" :value="key">{{ label }}</option>
          </select>
          <button v-if="canManage" class="ink-primary-button" @click="$emit('new')">新建需求</button>
        </div>
      </header>

      <p v-if="loading" class="req-hint">正在取卷…</p>
      <p v-else-if="!items.length" class="req-hint">还没有需求{{ canManage ? '——点右上角新建第一条。' : '。' }}</p>
      <ul v-else class="req-rows">
        <li v-for="item in items" :key="item.requirementId">
          <button
            class="req-row"
            :class="{ 'is-active': detail && detail.requirementId === item.requirementId }"
            @click="$emit('open', item)"
          >
            <code class="req-code">{{ item.code }}</code>
            <span class="req-title">{{ item.title }}</span>
            <span class="req-badge" :data-status="item.status">{{ statusLabels[item.status] || item.status }}</span>
            <span class="req-meta">
              <span class="req-priority" :data-priority="item.priority">{{ priorityLabel(item.priority) }}</span>
              <span>{{ item.assigneeName || '未指派' }}</span>
              <span>AC×{{ item.acCount }}</span>
            </span>
          </button>
        </li>
      </ul>
    </section>

    <section v-if="editing" class="ink-panel req-form ink-reveal">
      <header class="panel-head">
        <div>
          <span class="ink-eyebrow">{{ form.requirementId ? '编辑需求' : '新建需求' }}</span>
          <h2>{{ form.requirementId ? form.title || '编辑需求' : '立一条新需求' }}</h2>
        </div>
        <button class="ink-outline-button" @click="$emit('cancel-edit')">取消</button>
      </header>
      <form class="form-grid" @submit.prevent="$emit('save')">
        <label class="span-2">
          <span>标题</span>
          <input v-model="form.title" class="ink-field" placeholder="订单取消库存释放" required />
        </label>
        <label>
          <span>优先级</span>
          <select v-model="form.priority" class="ink-field">
            <option value="HIGH">高</option>
            <option value="MEDIUM">中</option>
            <option value="LOW">低</option>
          </select>
        </label>
        <label class="span-3">
          <span>背景</span>
          <textarea v-model="form.background" class="ink-field" rows="2" placeholder="为什么要做这件事" />
        </label>
        <label class="span-3">
          <span>描述</span>
          <textarea v-model="form.description" class="ink-field" rows="3" placeholder="要做成什么样子" />
        </label>
        <fieldset class="span-3 ac-editor">
          <legend>验收标准(AC)</legend>
          <div v-for="(ac, index) in form.acceptanceCriteria" :key="index" class="ac-row">
            <span class="ac-no">AC{{ index + 1 }}</span>
            <input v-model="ac.text" class="ink-field" placeholder="取消后 5 分钟内库存回补" />
            <button type="button" class="ink-text-button is-danger" @click="$emit('remove-ac', index)">删</button>
          </div>
          <button type="button" class="ink-text-button" @click="$emit('add-ac')">+ 增加一条 AC</button>
        </fieldset>
        <div class="form-actions span-3">
          <button class="ink-primary-button" type="submit" :disabled="saving">
            {{ saving ? '处理中…' : (form.requirementId ? '保存修改' : '创建需求') }}
          </button>
        </div>
      </form>
    </section>

    <section v-else-if="detail" class="ink-panel req-detail ink-reveal">
      <header class="panel-head">
        <div>
          <span class="ink-eyebrow">{{ detail.code }} · {{ statusLabels[detail.status] || detail.status }}</span>
          <h2>{{ detail.title }}</h2>
        </div>
        <button
          v-if="canManage && !isLocked"
          class="ink-outline-button"
          @click="$emit('edit', detail)"
        >编辑</button>
      </header>

      <dl class="detail-meta">
        <div><dt>优先级</dt><dd>{{ priorityLabel(detail.priority) }}</dd></div>
        <div><dt>指派</dt><dd>{{ detail.assigneeName || '未指派' }}</dd></div>
        <div><dt>更新</dt><dd>{{ fmtDate(detail.updatedAt) }}</dd></div>
      </dl>

      <div v-if="detail.background" class="detail-block"><h3>背景</h3><p>{{ detail.background }}</p></div>
      <div v-if="detail.description" class="detail-block"><h3>描述</h3><p>{{ detail.description }}</p></div>

      <div class="detail-block">
        <h3>验收标准</h3>
        <p v-if="!detail.acceptanceCriteria.length" class="req-hint">尚未填写 AC——需求体检会把这标为完整性问题。</p>
        <ol v-else class="ac-list">
          <li v-for="ac in detail.acceptanceCriteria" :key="ac.acId">{{ ac.text }}</li>
        </ol>
      </div>

      <div v-if="canManage" class="detail-block">
        <h3>指派开发</h3>
        <div class="assign-row">
          <select ref="assignSelect" class="ink-field assign-select" :key="detail.requirementId">
            <option v-for="member in assignableMembers" :key="member.userId" :value="member.userId">
              {{ member.nickname || member.username }}({{ member.username }})
            </option>
          </select>
          <button class="ink-outline-button" :disabled="!assignableMembers.length" @click="emitAssign">指派</button>
        </div>
      </div>

      <div v-if="nextStatuses.length" class="detail-block">
        <h3>状态流转</h3>
        <div class="transition-row">
          <button
            v-for="status in nextStatuses"
            :key="status"
            class="ink-outline-button"
            :class="{ 'is-danger-outline': status === 'CANCELED' }"
            @click="$emit('transition', detail, status)"
          >→ {{ statusLabels[status] || status }}</button>
        </div>
      </div>
    </section>

    <section v-else class="ink-panel req-empty ink-reveal">
      <p class="req-hint">从左侧选择一条需求查看详情{{ canManage ? ',或新建一条。' : '。' }}</p>
    </section>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { fmtDate } from '../../utils/format.js'
import { REQUIREMENT_EDGES, REQUIREMENT_STATUS_LABELS } from '../../composables/useRequirements.js'

// 研发任务页(P1b)。展示 + 事件上抛;可用流转按钮按前端边表裁剪(与后端同图),
// 角色裁剪:canManage=LEADER 才有新建/编辑/指派;IN_DEVELOPMENT→IN_REVIEW 额外对被指派人开放。
const props = defineProps({
  items: { type: Array, default: () => [] },
  total: { type: Number, default: 0 },
  detail: { type: Object, default: null },
  form: { type: Object, required: true },
  members: { type: Array, default: () => [] },
  filter: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  saving: { type: Boolean, default: false },
  editing: { type: Boolean, default: false },
  canManage: { type: Boolean, default: false },
  currentUserId: { type: [Number, String], default: null },
})
const emit = defineEmits(['open', 'new', 'edit', 'cancel-edit', 'save', 'add-ac', 'remove-ac',
  'assign', 'transition', 'filter'])

const statusLabels = REQUIREMENT_STATUS_LABELS
const assignSelect = ref(null)

const isLocked = computed(() => props.detail
  && ['IN_DEVELOPMENT', 'IN_REVIEW', 'DONE'].includes(props.detail.status))

const assignableMembers = computed(() => props.members)

const nextStatuses = computed(() => {
  if (!props.detail) return []
  const edges = REQUIREMENT_EDGES[props.detail.status] || []
  if (props.canManage) return edges
  // 非 LEADER:仅被指派人在开发中可提审
  const isAssignee = props.currentUserId != null
    && String(props.detail.assigneeId) === String(props.currentUserId)
  return props.detail.status === 'IN_DEVELOPMENT' && isAssignee ? ['IN_REVIEW'] : []
})

function emitAssign() {
  const value = assignSelect.value && assignSelect.value.value
  if (value) emit('assign', props.detail, Number(value))
}

function priorityLabel(priority) {
  return { HIGH: '高', MEDIUM: '中', LOW: '低' }[priority] || priority
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

.req-layout { display: grid; grid-template-columns: minmax(0, 5fr) minmax(0, 7fr); gap: var(--ink-sp-16); align-items: start; }

.panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--ink-sp-12); flex-wrap: wrap; }
.panel-head h1, .panel-head h2 { margin: var(--ink-sp-4) 0 0; font-family: var(--ink-font-display); color: var(--ink-strong); }
.panel-head h1 { font-size: var(--ink-fs-24); }
.panel-head h2 { font-size: var(--ink-fs-20); }
.head-actions { display: flex; align-items: center; gap: var(--ink-sp-8); }

.ink-field {
  padding: var(--ink-sp-8) var(--ink-sp-12);
  background: var(--ink-wash-field);
  border: 1px solid var(--line-soft);
  border-radius: var(--ink-radius-control);
  color: var(--ink-default);
  font: inherit;
  width: 100%;
}
.ink-field:focus-visible { outline: 2px solid var(--mineral-cyan); outline-offset: 1px; border-color: var(--line-strong); }
.filter-select { width: auto; }

.req-hint { margin: var(--ink-sp-16) 0 0; color: var(--ink-muted); }

.req-rows { list-style: none; margin: var(--ink-sp-16) 0 0; padding: 0; display: grid; gap: var(--ink-sp-8); }
.req-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: var(--ink-sp-4) var(--ink-sp-12);
  width: 100%;
  padding: var(--ink-sp-12) var(--ink-sp-16);
  background: var(--surface-paper);
  border: 1px solid var(--line-soft);
  border-radius: var(--ink-radius-control);
  color: var(--ink-default);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.req-row:focus-visible { outline: 2px solid var(--mineral-cyan); outline-offset: -2px; }
.req-row.is-active { border-color: var(--mineral-cyan); box-shadow: 0 0 0 3px var(--ink-glow-cyan); }
.req-code { color: var(--ink-muted); font-family: var(--ink-font-code); font-size: var(--ink-fs-12); align-self: center; }
.req-title { color: var(--ink-strong); font-size: var(--ink-fs-14); align-self: center; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.req-badge { justify-self: end; padding: 2px var(--ink-sp-8); border: 1px solid var(--line-soft); border-radius: 999px; color: var(--ink-muted); font-size: var(--ink-fs-12); }
.req-badge[data-status='IN_DEVELOPMENT'], .req-badge[data-status='IN_REVIEW'] { color: var(--mineral-cyan); border-color: var(--mineral-cyan); }
.req-badge[data-status='DONE'] { color: var(--ink-strong); border-color: var(--line-strong); }
.req-badge[data-status='CANCELED'] { opacity: 0.6; }
.req-meta { grid-column: 1 / -1; display: flex; gap: var(--ink-sp-12); color: var(--ink-muted); font-size: var(--ink-fs-12); }
.req-priority[data-priority='HIGH'] { color: var(--cinnabar); }

.form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--ink-sp-16); margin-top: var(--ink-sp-16); }
.form-grid label, .form-grid fieldset { display: grid; gap: var(--ink-sp-4); }
.form-grid label span, .form-grid legend { color: var(--ink-muted); font-size: var(--ink-fs-13); }
.span-2 { grid-column: span 2; }
.span-3 { grid-column: 1 / -1; }
.form-actions { display: flex; justify-content: flex-end; }
textarea.ink-field { resize: vertical; }

.ac-editor { border: 1px dashed var(--line-soft); border-radius: var(--ink-radius-control); padding: var(--ink-sp-12); }
.ac-row { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: var(--ink-sp-8); align-items: center; margin-bottom: var(--ink-sp-8); }
.ac-no { color: var(--ink-muted); font-family: var(--ink-font-code); font-size: var(--ink-fs-12); }

.detail-meta { display: flex; gap: var(--ink-sp-24); margin: var(--ink-sp-16) 0 0; }
.detail-meta div { display: grid; gap: 2px; }
.detail-meta dt { color: var(--ink-muted); font-size: var(--ink-fs-12); }
.detail-meta dd { margin: 0; color: var(--ink-strong); font-size: var(--ink-fs-14); }

.detail-block { margin-top: var(--ink-sp-16); }
.detail-block h3 { margin: 0 0 var(--ink-sp-8); font-family: var(--ink-font-display); font-size: var(--ink-fs-14); color: var(--ink-strong); }
.detail-block p { margin: 0; color: var(--ink-default); line-height: var(--ink-lh-body); white-space: pre-wrap; }

.ac-list { margin: 0; padding-left: 1.2em; display: grid; gap: var(--ink-sp-4); color: var(--ink-default); }

.assign-row, .transition-row { display: flex; gap: var(--ink-sp-8); flex-wrap: wrap; align-items: center; }
.assign-select { width: auto; min-width: 200px; }
.is-danger-outline { color: var(--cinnabar); border-color: var(--cinnabar); }
.ink-text-button.is-danger { color: var(--cinnabar); }

@media (max-width: 1023px) {
  .req-layout { grid-template-columns: 1fr; }
  .form-grid { grid-template-columns: 1fr; }
  .span-2 { grid-column: auto; }
}
</style>
