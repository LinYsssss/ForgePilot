<template>
  <div class="ink-annotation-rail">
    <ol v-if="notes.length" class="annotations">
      <li v-for="note in notes" :key="note.id" :class="{ 'is-critical': note.tone === 'critical' }">
        <time>{{ note.time }}</time>
        <strong>{{ note.title }}</strong>
        <p v-if="note.body">{{ note.body }}</p>
        <span v-if="note.status" class="note-status">{{ note.status }}</span>
        <button
          v-if="note.kind === 'finding'"
          type="button"
          class="note-locate"
          @click="emit('locate', note)"
        >定位证据</button>
      </li>
    </ol>
    <p v-else class="rail-empty">暂无批注：装载 Agent Run 后，Finding 与关键步骤会在此归档。</p>

    <section v-if="facts.length" class="case-facts" aria-label="案卷简目">
      <h3>案卷简目</h3>
      <dl>
        <div v-for="fact in facts" :key="fact.dt">
          <dt>{{ fact.dt }}</dt>
          <dd>{{ fact.dd }}</dd>
        </div>
      </dl>
    </section>

    <blockquote class="rail-quote">
      "所有阻断结论，必须能回到持久化证据。"<cite>— RepoSage 守门规范</cite>
    </blockquote>
  </div>
</template>

<script setup>
// 朱批栏内容(合同 §5 AnnotationRail):来源 + 时间 + 状态 + 定位锚点。
// 容器/抽屉行为由 InkShell 承担;本组件是纯展示时间线,批注全部由
// workspaceModel.railNotes 从真实 Finding/Timeline/Run 派生,不虚构事件。
defineProps({
  notes: { type: Array, default: () => [] },   // railNotes() 输出
  facts: { type: Array, default: () => [] },   // [{dt, dd}] 案卷简目
})
const emit = defineEmits(['locate'])
</script>

<style scoped>
.ink-annotation-rail { display: grid; gap: var(--ink-sp-24); }

/* ---- 批注时间线(细线关联,不画飞线;合同 §5) ---- */
.annotations {
  position: relative;
  margin: 0;
  padding: 0 0 0 19px;
  list-style: none;
}
.annotations::before {
  content: "";
  position: absolute;
  top: 8px;
  bottom: 10px;
  left: 4px;
  width: 1px;
  background: var(--line-strong);
}
.annotations li {
  position: relative;
  margin-bottom: var(--ink-sp-16);
  padding: var(--ink-sp-12);
  background: var(--ink-wash-panel-faint);
  border: 1px solid var(--line-soft);
}
.annotations li:last-child { margin-bottom: 0; }
.annotations li::before {
  content: "";
  position: absolute;
  top: 15px;
  left: -19px;
  width: 8px;
  height: 8px;
  background: var(--line-strong);
  border: 3px solid var(--surface-muted);
  border-radius: 50%;
}
.annotations li.is-critical { border-left: 2px solid var(--cinnabar); }
.annotations li.is-critical::before { background: var(--cinnabar); }

.annotations time { display: block; color: var(--ink-muted); font-size: var(--ink-fs-12); }
.annotations strong {
  display: block;
  margin-top: 5px;
  color: var(--ink-strong);
  font-size: var(--ink-fs-13);
  overflow-wrap: anywhere;
}
.annotations p {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin: 5px 0 0;
  color: var(--ink-default);
  font-size: var(--ink-fs-12);
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.note-status {
  display: inline-block;
  margin-top: 6px;
  margin-right: var(--ink-sp-8);
  color: var(--ink-muted);
  font-size: var(--ink-fs-12);
}
.note-locate {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  margin-top: 2px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--mineral-cyan);
  font-size: var(--ink-fs-12);
  font-weight: 700;
}
.note-locate:hover { text-decoration: underline; }

.rail-empty { margin: 0; color: var(--ink-muted); font-size: var(--ink-fs-12); line-height: 1.7; }

/* ---- 案卷简目 ---- */
.case-facts {
  padding: var(--ink-sp-12) var(--ink-sp-16);
  background: var(--ink-wash-panel-faint);
  border: 1px solid var(--line-soft);
}
.case-facts h3 {
  margin: 0 0 var(--ink-sp-8);
  font-family: var(--ink-font-display);
  font-size: var(--ink-fs-14);
}
.case-facts dl { margin: 0; }
.case-facts dl div {
  display: flex;
  justify-content: space-between;
  gap: var(--ink-sp-12);
  padding: 5px 0;
  border-top: 1px solid var(--line-soft);
  font-size: var(--ink-fs-12);
}
.case-facts dt { color: var(--ink-muted); }
.case-facts dd { margin: 0; color: var(--ink-strong); overflow-wrap: anywhere; text-align: right; }

.rail-quote {
  margin: 0 2px;
  padding: var(--ink-sp-12) 0 var(--ink-sp-12) var(--ink-sp-16);
  color: var(--ink-muted);
  border-left: 2px solid var(--cinnabar);
  font-family: var(--ink-font-display);
  font-size: var(--ink-fs-13);
  line-height: 1.7;
}
.rail-quote cite {
  display: block;
  margin-top: 5px;
  font-family: var(--ink-font-body);
  font-size: var(--ink-fs-12);
  font-style: normal;
}
</style>
