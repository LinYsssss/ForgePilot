<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";

import type { Finding } from "./api";
import type { ReviewContextChangedFile } from "./context";
import { isFindingLine, parseUnifiedDiff } from "./diff";

const props = defineProps<{
  files: ReviewContextChangedFile[];
  selectedPath: string | null;
  finding: Finding | null;
}>();

const emit = defineEmits<{ selectPath: [path: string] }>();
const root = ref<HTMLElement | null>(null);

const activeFile = computed(
  () =>
    props.files.find((file) => file.path === props.selectedPath) ??
    props.files[0] ??
    null,
);

const rows = computed(() =>
  activeFile.value?.patch === null || activeFile.value?.patch === undefined
    ? []
    : parseUnifiedDiff(activeFile.value.patch),
);

function selected(row: (typeof rows.value)[number]): boolean {
  return (
    props.finding !== null &&
    props.finding.path === activeFile.value?.path &&
    isFindingLine(row, props.finding.line)
  );
}

watch(
  [() => props.finding?.id, activeFile],
  async () => {
    await nextTick();
    const line = root.value?.querySelector<HTMLElement>(".diff-line-selected");
    if (line !== null && typeof line?.scrollIntoView === "function") {
      line.scrollIntoView({ block: "center" });
    }
  },
);
</script>

<template>
  <div ref="root" class="diff-viewer">
    <aside class="diff-files" aria-label="审查快照中的改动文件">
      <button
        v-for="file in files"
        :key="file.path"
        type="button"
        :class="['diff-file', { 'diff-file-active': file.path === activeFile?.path }]"
        @click="emit('selectPath', file.path)"
      >
        <span>{{ file.path }}</span>
        <small>{{ file.changeType }}</small>
      </button>
      <p v-if="files.length === 0" class="empty-state">快照中没有改动文件。</p>
    </aside>

    <div class="diff-content">
      <div v-if="activeFile" class="diff-toolbar">
        <code>{{ activeFile.path }}</code>
        <span class="badge badge-neutral">{{ activeFile.changeType }}</span>
      </div>
      <p v-if="activeFile?.patch === null" class="empty-state">提供方没有返回这个文件的 patch。</p>
      <p v-else-if="activeFile === null" class="empty-state">选择一个文件查看不可变 patch。</p>
      <div v-else class="diff-scroll" role="region" aria-label="统一 Diff" tabindex="0">
        <div
          v-for="row in rows"
          :key="row.key"
          :class="['diff-line', `diff-line-${row.kind}`, { 'diff-line-selected': selected(row) }]"
        >
          <span class="diff-old-line">{{ row.oldLine ?? "" }}</span>
          <span class="diff-new-line">{{ row.newLine ?? "" }}</span>
          <code>{{ row.text }}</code>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.diff-viewer {
  display: grid;
  min-height: 28rem;
  overflow: hidden;
  border: 0.0625rem solid var(--fp-color-border);
  border-radius: var(--fp-radius-md);
  background: var(--fp-color-canvas-muted);
  grid-template-columns: minmax(13rem, 0.34fr) minmax(0, 1fr);
}

.diff-files {
  max-height: 38rem;
  overflow: auto;
  border-right: 0.0625rem solid var(--fp-color-border);
  background: var(--fp-color-surface-strong);
}

.diff-file {
  display: grid;
  gap: var(--fp-space-1);
  width: 100%;
  padding: var(--fp-space-3);
  border: 0;
  border-bottom: 0.0625rem solid var(--fp-color-border);
  background: transparent;
  color: var(--fp-color-text-muted);
  cursor: pointer;
  text-align: left;
}

.diff-file:hover,
.diff-file-active {
  background: var(--fp-color-surface-header-active);
  color: var(--fp-color-text);
}

.diff-file-active {
  box-shadow: inset 0.1875rem 0 var(--fp-color-accent);
}

.diff-file span {
  overflow-wrap: anywhere;
}

.diff-file small {
  color: var(--fp-color-text-subtle);
  font: 0.6875rem/1.2 var(--fp-font-mono);
}

.diff-content {
  min-width: 0;
}

.diff-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fp-space-3);
  min-height: 3.25rem;
  padding: var(--fp-space-3) var(--fp-space-4);
  border-bottom: 0.0625rem solid var(--fp-color-border);
  background: var(--fp-color-surface-strong);
}

.diff-scroll {
  max-height: 35rem;
  overflow: auto;
  font: 0.75rem/1.55 var(--fp-font-mono);
}

.diff-line {
  display: grid;
  min-width: max-content;
  grid-template-columns: 3.5rem 3.5rem minmax(32rem, 1fr);
}

.diff-line > span,
.diff-line > code {
  min-height: 1.45rem;
  padding: 0 var(--fp-space-2);
  white-space: pre;
}

.diff-line > span {
  border-right: 0.0625rem solid var(--fp-color-border-subtle);
  color: var(--fp-color-text-subtle);
  text-align: right;
  user-select: none;
}

.diff-line > code {
  color: var(--fp-color-text-muted);
}

.diff-line-addition { background: var(--fp-color-diff-added-soft); }
.diff-line-deletion { background: var(--fp-color-diff-removed-soft); }
.diff-line-hunk { background: var(--fp-color-secondary-soft); }
.diff-line-selected { box-shadow: inset 0 0 0 0.125rem var(--fp-color-warning); }
.diff-line-selected > code { color: var(--fp-color-text); }

@media (max-width: 64rem) {
  .diff-viewer { grid-template-columns: 1fr; }
  .diff-files { display: flex; max-height: none; overflow-x: auto; border-right: 0; border-bottom: 0.0625rem solid var(--fp-color-border); }
  .diff-file { min-width: 14rem; border-right: 0.0625rem solid var(--fp-color-border); border-bottom: 0; }
}
</style>
