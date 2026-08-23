<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { parseId, PROJECT_QUERY_KEY } from "../../app/routes";
import { formatDateTime } from "../../lib/datetime";
import { apiErrorMessage } from "../../lib/http";
import { listProjects, type Project } from "../project/api";
import { listProjectKnowledge, uploadProjectKnowledge, type KnowledgeDocument } from "./api";

const route = useRoute();
const router = useRouter();
const projectId = computed(() => parseId(route.query[PROJECT_QUERY_KEY]));
const projects = ref<Project[]>([]);
const documents = ref<KnowledgeDocument[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const file = ref<File | null>(null);
const uploadPending = ref(false);
const uploadError = ref<string | null>(null);
const selectedProject = computed(() => projects.value.find((project) => project.id === projectId.value) ?? null);
const isLeader = computed(() => selectedProject.value?.myRole === "LEADER");
const summary = computed(() => ({
  ready: documents.value.filter((document) => document.status === "READY").length,
  chunks: documents.value.reduce((total, document) => total + document.chunkCount, 0),
  embedded: documents.value.reduce((total, document) => total + document.embeddedChunkCount, 0),
  dimensions: [...new Set(documents.value.map((document) => document.embeddingDimension).filter((value): value is number => value !== null))],
}));

function selectProject(event: Event): void {
  const id = parseId((event.target as HTMLSelectElement).value);
  if (id !== null) void router.push({ name: "knowledge", query: { [PROJECT_QUERY_KEY]: String(id) } });
}

async function load(): Promise<void> {
  const id = projectId.value;
  documents.value = [];
  error.value = null;
  if (id === null) return;
  loading.value = true;
  try { documents.value = await listProjectKnowledge(id); } catch (failure: unknown) { error.value = apiErrorMessage(failure); } finally { loading.value = false; }
}

function pickFile(event: Event): void { file.value = (event.target as HTMLInputElement).files?.[0] ?? null; }

async function upload(): Promise<void> {
  const id = projectId.value;
  const selected = file.value;
  if (id === null || selected === null) return;
  uploadPending.value = true;
  uploadError.value = null;
  try {
    const text = await selected.text();
    await uploadProjectKnowledge(id, { title: selected.name, text });
    file.value = null;
    await load();
  } catch (failure: unknown) { uploadError.value = apiErrorMessage(failure); } finally { uploadPending.value = false; }
}

onMounted(async () => { try { projects.value = await listProjects(); } catch (failure: unknown) { error.value = apiErrorMessage(failure); } });
watch(projectId, load, { immediate: true });
</script>

<template>
  <section aria-labelledby="knowledge-title">
    <div class="page-head"><p class="eyebrow">Semantic project context</p><h1 id="knowledge-title">项目知识</h1><p class="lede">文本和 Markdown 被切片、Embedding 并建立项目级语义索引；页面只展示真实索引元数据，不展示原始向量。</p></div>
    <section class="panel project-selector"><div><h2 class="panel-title">项目上下文</h2><p class="field-hint">附件只有显式提升后才成为此处的公共知识。</p></div><div class="field"><label for="knowledge-project">当前项目</label><select id="knowledge-project" :value="projectId ?? ''" @change="selectProject"><option value="" disabled>请选择项目</option><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option></select></div></section>
    <p v-if="projectId === null" class="empty-state">选择一个项目以查看语义知识索引。</p>
    <template v-else>
      <p v-if="error" class="alert" role="alert">{{ error }}</p>
      <section class="knowledge-summary" aria-label="语义索引汇总"><div class="panel"><strong>{{ documents.length }}</strong><span>知识文档</span></div><div class="panel"><strong>{{ summary.ready }}/{{ documents.length }}</strong><span>语义索引已就绪</span></div><div class="panel"><strong>{{ summary.embedded }}/{{ summary.chunks }}</strong><span>Embedding Chunk</span></div><div class="panel"><strong>{{ summary.dimensions.join(" / ") || "—" }}</strong><span>向量维度</span></div></section>
      <section v-if="isLeader" class="panel" aria-labelledby="knowledge-upload-title"><h2 id="knowledge-upload-title" class="panel-title">上传项目知识</h2><p class="field-hint">支持 .txt 与 .md；文件正文仅提交给项目知识入库接口。</p><form class="inline-form" @submit.prevent="upload"><div class="field"><label for="knowledge-file">文本或 Markdown 文件</label><input id="knowledge-file" type="file" accept=".txt,.md,text/plain,text/markdown" @change="pickFile" /><p v-if="file" class="field-hint">已选择：{{ file.name }}</p></div><button class="button button-primary" :disabled="file === null || uploadPending">{{ uploadPending ? "正在建立索引…" : "上传并建立索引" }}</button></form><p v-if="uploadError" class="alert" role="alert">{{ uploadError }}</p></section>
      <p v-else-if="selectedProject" class="empty-state">你可查看项目知识及其索引状态；只有负责人可以上传。</p>
      <section class="panel" aria-labelledby="knowledge-list-title"><div class="index-head"><div><p class="eyebrow">Vector retrieval profile</p><h2 id="knowledge-list-title" class="panel-title">公共知识文档</h2></div><span class="badge badge-info">语义索引</span></div><p v-if="loading" class="muted">正在加载知识索引…</p><p v-else-if="documents.length === 0" class="empty-state">该项目还没有公共知识文档。</p><ol v-else class="record-list"><li v-for="document in documents" :key="document.id" class="record"><div class="record-head"><h3 class="record-title">{{ document.title }}</h3><span class="badge" :class="document.status === 'READY' ? 'badge-success' : document.status === 'FAILED' ? 'badge-danger' : 'badge-warning'">{{ document.status }}</span></div><dl class="meta-list"><div><dt>Chunk / 向量</dt><dd>{{ document.chunkCount }} / {{ document.embeddedChunkCount }}</dd></div><div><dt>向量维度</dt><dd>{{ document.embeddingDimension ?? "未就绪" }}</dd></div><div><dt>Embedding Profile</dt><dd>{{ [document.embeddingProvider, document.embeddingModel, document.embeddingVersion].filter(Boolean).join(" · ") || "未记录" }}</dd></div><div><dt>更新时间</dt><dd>{{ formatDateTime(document.updatedAt) }}</dd></div></dl><p v-if="document.failureReason" class="alert" role="alert">{{ document.failureReason }}</p></li></ol></section>
    </template>
  </section>
</template>

<style scoped>
.knowledge-summary { display: grid; gap: var(--fp-space-4); grid-template-columns: repeat(4, minmax(0, 1fr)); }
.knowledge-summary .panel { display: grid; gap: var(--fp-space-2); margin-bottom: var(--fp-space-6); }
.knowledge-summary strong { color: var(--fp-color-accent-inverse); font: 800 1.5rem/1 var(--fp-font-mono); }
.knowledge-summary span { color: var(--fp-color-text-muted); font-size: 0.75rem; }
@media (max-width: 64rem) { .knowledge-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 42rem) { .knowledge-summary { grid-template-columns: 1fr; } }
</style>
