<template>
  <div class="ink-projects">
    <section class="ink-panel proj-form ink-reveal">
      <header class="panel-head">
        <div>
          <span class="ink-eyebrow">{{ form.projectId ? '编辑项目' : '创建项目' }}</span>
          <h1>{{ form.projectId ? form.name || '编辑项目' : '开设新卷宗' }}</h1>
        </div>
        <button v-if="form.projectId" class="ink-outline-button" @click="$emit('reset')">取消编辑</button>
      </header>

      <form class="form-grid" @submit.prevent="$emit('save')">
        <label>
          <span>项目名称</span>
          <input v-model="form.name" class="ink-field" placeholder="mall-order-service" required />
        </label>
        <label>
          <span>默认分支</span>
          <input v-model="form.defaultBranch" class="ink-field" placeholder="main" />
        </label>
        <label>
          <span>描述</span>
          <input v-model="form.description" class="ink-field" placeholder="电商订单服务" />
        </label>
        <div class="form-actions">
          <button class="ink-primary-button" type="submit" :disabled="saving">
            {{ saving ? '处理中…' : (form.projectId ? '保存修改' : '创建项目') }}
          </button>
        </div>
      </form>
    </section>

    <section class="ink-panel proj-list ink-reveal">
      <header class="panel-head">
        <div><span class="ink-eyebrow">我的项目</span><h2>共 {{ projects.length }} 个</h2></div>
      </header>

      <p v-if="loading" class="proj-hint">正在取卷…</p>
      <p v-else-if="!projects.length" class="proj-hint">还没有项目——在上方开设第一个。</p>
      <ul v-else class="proj-grid">
        <li v-for="project in projects" :key="project.projectId">
          <article class="proj-card" :class="{ 'is-active': isActive(project) }">
            <button
              class="proj-open"
              :aria-current="isActive(project) ? 'true' : undefined"
              @click="$emit('select', project)"
            >
              <span class="proj-status">{{ project.status }}</span>
              <h3>{{ project.name }}</h3>
              <p class="proj-desc">{{ project.description || '暂无描述' }}</p>
              <span class="proj-meta">
                <code>{{ project.defaultBranch }}</code>
                <span>{{ fmtDate(project.createdAt) }}</span>
              </span>
            </button>
            <div class="proj-actions">
              <button class="ink-text-button" @click="$emit('edit', project)">编辑</button>
              <button class="ink-text-button is-danger" @click="$emit('remove', project)">删除</button>
            </div>
          </article>
        </li>
      </ul>
    </section>
  </div>
</template>

<script setup>
import { fmtDate } from '../../utils/format.js'

// 墨境项目页(实施步骤 8 第二页)。纯展示 + 事件上抛:表单对象由页面直接传入既有
// projectForm 引用,沿用 useProjects 的双向绑定语义,不复制一份本地表单状态——
// 复制会让「编辑某项目 → 表单预填」这条既有行为出现第二个真源。
//
// 卡片拆成「打开」按钮 + 独立动作区:旧版把整卡做成可点区域再用 @click.stop 挡住内部按钮,
// 键盘用户会先聚到卡片、再聚到按钮,读屏时"卡片"本身是个没有名字的可点元素。
const props = defineProps({
  projects: { type: Array, default: () => [] },
  activeProject: { type: Object, default: null },
  form: { type: Object, required: true },
  loading: { type: Boolean, default: false },
  saving: { type: Boolean, default: false },
})
defineEmits(['select', 'edit', 'remove', 'save', 'reset'])

function isActive(project) {
  return !!props.activeProject && props.activeProject.projectId === project.projectId
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

.ink-projects { display: grid; gap: var(--ink-sp-16); }

.panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--ink-sp-12); flex-wrap: wrap; }
.panel-head h1, .panel-head h2 { margin: var(--ink-sp-4) 0 0; font-family: var(--ink-font-display); color: var(--ink-strong); }
.panel-head h1 { font-size: var(--ink-fs-28); }
.panel-head h2 { font-size: var(--ink-fs-20); }

.form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--ink-sp-16); margin-top: var(--ink-sp-16); }
.form-grid label { display: grid; gap: var(--ink-sp-4); }
.form-grid label span { color: var(--ink-muted); font-size: var(--ink-fs-13); }
.form-actions { grid-column: 1 / -1; display: flex; justify-content: flex-end; }

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

.proj-hint { margin: var(--ink-sp-16) 0 0; color: var(--ink-muted); }

.proj-grid { list-style: none; margin: var(--ink-sp-16) 0 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(248px, 1fr)); gap: var(--ink-sp-12); }
.proj-card {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--surface-paper);
  border: 1px solid var(--line-soft);
  border-radius: var(--ink-radius-control);
}
/* --ink-glow-cyan 是颜色不是阴影:必须自带偏移/扩散,否则整条 box-shadow 被判无效丢弃,
   选中态就只剩边框换色这一重编码。 */
.proj-card.is-active { border-color: var(--mineral-cyan); box-shadow: 0 0 0 3px var(--ink-glow-cyan); }

.proj-open {
  flex: 1;
  display: grid;
  gap: var(--ink-sp-8);
  padding: var(--ink-sp-16);
  background: none;
  border: 0;
  color: var(--ink-default);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.proj-open:focus-visible { outline: 2px solid var(--mineral-cyan); outline-offset: -2px; }
.proj-open h3 { margin: 0; font-family: var(--ink-font-display); font-size: var(--ink-fs-16); color: var(--ink-strong); }
.proj-status { color: var(--ink-muted); font-size: var(--ink-fs-12); letter-spacing: 0.08em; }
.proj-desc { margin: 0; color: var(--ink-muted); font-size: var(--ink-fs-13); }
.proj-meta { display: flex; justify-content: space-between; gap: var(--ink-sp-8); color: var(--ink-muted); font-size: var(--ink-fs-12); }
.proj-meta code { font-family: var(--ink-font-code); }

.proj-actions { display: flex; gap: var(--ink-sp-8); padding: 0 var(--ink-sp-16) var(--ink-sp-12); }
.ink-text-button.is-danger { color: var(--cinnabar); }

@media (max-width: 767px) {
  .form-grid { grid-template-columns: 1fr; }
}
</style>
