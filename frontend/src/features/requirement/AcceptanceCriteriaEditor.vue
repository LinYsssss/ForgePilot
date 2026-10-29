<script setup lang="ts">
import type { AcceptanceCriterionDraft } from "./api";

const props = defineProps<{ idPrefix: string }>();
const rows = defineModel<AcceptanceCriterionDraft[]>({ required: true });

function inputId(index: number): string {
  return `${props.idPrefix}-ac-${index}`;
}

function add(): void {
  rows.value = [...rows.value, { text: "" }];
}

function remove(index: number): void {
  rows.value = rows.value.filter((_, position) => position !== index);
}

function move(index: number, offset: number): void {
  const next = [...rows.value];
  const [moved] = next.splice(index, 1);
  next.splice(index + offset, 0, moved);
  rows.value = next;
}
</script>

<template>
  <fieldset class="ac-editor">
    <legend>验收标准</legend>
    <p class="field-hint">顺序即服务端的 sortOrder；已有条目的 acKey 保持不变。</p>

    <ol class="ac-list">
      <li v-for="(row, index) in rows" :key="index" class="ac-row">
        <div class="field">
          <label :for="inputId(index)">
            {{ index + 1 }}.
            <span class="badge badge-neutral">{{ row.acKey ?? "新增" }}</span>
          </label>
          <input :id="inputId(index)" v-model="row.text" required />
        </div>
        <div class="ac-row-actions">
          <button
            type="button"
            class="button button-quiet"
            :disabled="index === 0"
            @click="move(index, -1)"
          >
            上移
          </button>
          <button
            type="button"
            class="button button-quiet"
            :disabled="index === rows.length - 1"
            @click="move(index, 1)"
          >
            下移
          </button>
          <button
            type="button"
            class="button button-quiet"
            :disabled="rows.length === 1"
            @click="remove(index)"
          >
            删除
          </button>
        </div>
      </li>
    </ol>

    <button type="button" class="button button-quiet ac-add" @click="add">添加验收标准</button>
  </fieldset>
</template>

<style scoped>
.ac-list {
  display: grid;
  gap: var(--fp-space-2);
  margin: var(--fp-space-3) 0;
  padding: 0;
  list-style: none;
}

/* 一条 AC 占一行:输入框在左,排序与删除按钮贴右、与输入框底对齐。 */
.ac-row {
  display: grid;
  align-items: end;
  gap: var(--fp-space-3);
  grid-template-columns: minmax(0, 1fr) auto;
}

.ac-row .field {
  min-width: 0;
}

.ac-row .field label {
  display: flex;
  align-items: center;
  gap: var(--fp-space-2);
}

.ac-row-actions {
  display: flex;
  gap: var(--fp-space-2);
}

.ac-row-actions .button {
  min-height: 2.5rem;
  padding: var(--fp-space-1) var(--fp-space-3);
  font-size: 0.75rem;
}

@media (max-width: 42rem) {
  .ac-row {
    grid-template-columns: 1fr;
  }

  .ac-row-actions {
    justify-content: flex-end;
  }
}
</style>
