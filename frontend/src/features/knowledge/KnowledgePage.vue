<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";

import { parseId, knowledgeDocumentRoute, knowledgeRoute } from "../../app/routes";
import { useProjectSelection } from "../../composables/useProjectSelection";
import { formatDateTime } from "../../lib/datetime";
import { apiErrorMessage } from "../../lib/http";
import { useFinitePolling } from "../../composables/useFinitePolling";
import {
  deleteProjectKnowledge,
  getKnowledgeContent,
  knowledgeDownloadUrl,
  type KnowledgeDocumentContent,
  listProjectKnowledge,
  retryProjectKnowledge,
  uploadProjectKnowledge,
  type KnowledgeDocument,
  type KnowledgeStatus,
} from "./api";

const route = useRoute();
const router = useRouter();
const {
  projects,
  selectedProjectId: projectId,
  selectedProject,
  isLeader,
  error,
  select,
} = useProjectSelection({ routeName: "knowledge" });
const documents = ref<KnowledgeDocument[]>([]);
const selectedDocumentId = computed(() => parseId(route.query.document));
const content = ref<KnowledgeDocumentContent | null>(null);
const contentPending = ref(false);
const contentError = ref<string | null>(null);
let contentToken = 0;
watch([projectId, selectedDocumentId], async ([project, document]) => {
  const token = ++contentToken;
  content.value = null;
  contentError.value = null;
  contentPending.value = false;
  if (project === null || document === null) return;
  contentPending.value = true;
  try {
    const loaded = await getKnowledgeContent(project, document);
    if (token === contentToken) content.value = loaded;
  } catch (failure: unknown) {
    if (token === contentToken) contentError.value = apiErrorMessage(failure);
  } finally {
    if (token === contentToken) contentPending.value = false;
  }
}, { immediate: true });
const loading = ref(false);
const files = ref<File[]>([]);
const uploadPending = ref(false);
const uploadError = ref<string | null>(null);
/** 逐文件结果：批量上传是 N 次独立调用，因此每个文件各有自己的成败。 */
const uploadResults = ref<Array<{
  name: string;
  status: KnowledgeStatus | null;
  error: string | null;
}>>([]);
const deletePending = ref(false);
const retryPending = ref(false);
let listToken = 0;
const summary = computed(() => ({
  ready: documents.value.filter((document) => document.status === "READY").length,
  pending: documents.value.filter((document) => document.status === "PENDING").length,
  failed: documents.value.filter((document) => document.status === "FAILED").length,
}));
const pollingKey = computed(() => {
  const id = projectId.value;
  return id !== null && documents.value.some(document => document.status === "PENDING")
    ? `knowledge:${id}`
    : null;
});

const polling = useFinitePolling(pollingKey, async () => {
  const id = projectId.value;
  const token = listToken;
  if (id === null) return;
  const loaded = await listProjectKnowledge(id);
  if (id === projectId.value && token === listToken) documents.value = loaded;
});
const pollingError = computed(() => polling.error.value === null
  ? null
  : apiErrorMessage(polling.error.value));

function selectProject(event: Event): void {
  void select(parseId((event.target as HTMLSelectElement).value));
}

async function load(): Promise<void> {
  const token = ++listToken;
  const id = projectId.value;
  documents.value = [];
  error.value = null;
  loading.value = false;
  if (id === null) return;
  loading.value = true;
  try {
    const loaded = await listProjectKnowledge(id);
    if (token === listToken) documents.value = loaded;
  } catch (failure: unknown) {
    if (token === listToken) error.value = apiErrorMessage(failure);
  } finally {
    if (token === listToken) loading.value = false;
  }
}

function pickFiles(event: Event): void {
  files.value = [...((event.target as HTMLInputElement).files ?? [])];
  uploadResults.value = [];
}

/**
 * 逐文件独立上传，不是一个批量端点。每次调用都是后端自己的一个事务，
 * 因此一个文件失败既不会回滚已经成功的那些，也不会阻断后面的。
 * 每个文件会先作为 PENDING 文档被受理，再由后台逐个完成向量化。
 */
async function upload(): Promise<void> {
  const id = projectId.value;
  if (id === null || files.value.length === 0) return;
  uploadPending.value = true;
  uploadError.value = null;
  uploadResults.value = [];
  for (const selected of files.value) {
    try {
      const accepted = await uploadProjectKnowledge(id, {
        title: selected.name,
        text: await selected.text(),
      });
      uploadResults.value.push({ name: selected.name, status: accepted.status, error: null });
    } catch (failure: unknown) {
      uploadResults.value.push({
        name: selected.name,
        status: null,
        error: apiErrorMessage(failure),
      });
    }
  }
  files.value = [];
  uploadPending.value = false;
  await load();
}

/**
 * 硬删：chunk 与它的向量是派生数据，随文档消亡，此后 Guidance 与 Review 的检索
 * 都召不回它。需求附件来源的文档不给删除入口——附件关系是需求侧的事实，后端也会
 * 拒绝。
 */
async function remove(document: KnowledgeDocument): Promise<void> {
  const id = projectId.value;
  if (id === null || !window.confirm(`确认删除「${document.title}」及其全部向量切片？`)) return;
  deletePending.value = true;
  error.value = null;
  try {
    await deleteProjectKnowledge(id, document.id);
    if (selectedDocumentId.value === document.id) await router.replace(knowledgeRoute(id));
    await load();
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  } finally {
    deletePending.value = false;
  }
}

/**
 * 失败重试：后端只受理 FAILED。受理后回到「正在处理」，
 * 列表轮询会自动接上，无需在这里手工刷新状态。
 */
async function retry(document: KnowledgeDocument): Promise<void> {
  const id = projectId.value;
  if (id === null) return;
  retryPending.value = true;
  error.value = null;
  try {
    await retryProjectKnowledge(id, document.id);
    await load();
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  } finally {
    retryPending.value = false;
  }
}

watch(projectId, load, { immediate: true });
</script>

<template>
  <section aria-labelledby="knowledge-title">
    <div class="page-head">
      <div>
        <h1 id="knowledge-title">项目知识</h1>
        <p class="field-hint">处理完成的文档会用于 AI 审查和实现建议，可从审查引用回到原文核对。</p>
      </div>
    </div>
    <section class="panel project-selector">
      <div>
        <h2 class="panel-title">项目上下文</h2>
        <p class="field-hint">需求附件只有显式提升后，才会进入这里的公共知识检索。</p>
      </div>
      <div class="field">
        <label for="knowledge-project">当前项目</label>
        <select id="knowledge-project" :value="projectId ?? ''" @change="selectProject">
          <option value="" disabled>请选择项目</option>
          <option v-for="project in projects" :key="project.id" :value="project.id">
            {{ project.name }}
          </option>
        </select>
      </div>
    </section>
    <section v-if="projectId !== null && selectedDocumentId !== null" class="panel" aria-labelledby="document-reader-title">
      <h2 id="document-reader-title" class="panel-title">{{ content?.title ?? "阅读项目文档" }}</h2>
      <RouterLink :to="knowledgeRoute(projectId)">关闭阅读</RouterLink>
      <p v-if="contentPending" class="muted">正在读取文档…</p>
      <p v-if="contentError" class="alert" role="alert">{{ contentError }} 公共原文可能已删除或不可访问；需求附件请从对应需求页阅读，历史审查证据仍保留。</p>
      <template v-if="content">
        <a class="button button-quiet" :href="knowledgeDownloadUrl(projectId, content.documentId)">下载</a>
        <pre class="document-content">{{ content.text }}</pre>
      </template>
    </section>

    <p v-if="projectId === null" class="empty-state">选择一个项目以阅读项目知识。</p>
    <template v-else>
      <p v-if="error" class="alert" role="alert">{{ error }}</p>
      <p v-if="pollingError" class="alert" role="alert">
        文档状态自动刷新失败：{{ pollingError }}
        <button type="button" class="button button-quiet" @click="polling.retry">重新刷新</button>
      </p>
      <section class="knowledge-summary" aria-label="文档处理状态">
        <div class="panel"><strong>{{ documents.length }}</strong><span>知识文档</span></div>
        <div class="panel">
          <strong>{{ summary.ready }}</strong><span>可用于审查</span>
        </div>
        <div :class="['panel', { 'vector-metric': summary.pending > 0 }]">
          <strong>{{ summary.pending }}</strong><span>正在处理</span>
        </div>
        <div :class="['panel', { 'vector-metric': summary.failed > 0 }]">
          <strong>{{ summary.failed }}</strong><span>处理失败</span>
        </div>
      </section>
      <section v-if="isLeader" class="panel upload-panel" aria-labelledby="knowledge-upload-title">
        <div>
          <h2 id="knowledge-upload-title" class="panel-title">上传项目知识</h2>
          <p class="field-hint">
            支持 .txt 与 .md，可一次选择多个文件。逐个文件独立处理并各自报告结果，
            上传会先受理为“正在处理”，后台完成向量化后自动更新；某个文件失败不影响其余文件。
          </p>
        </div>
        <form class="inline-form" @submit.prevent="upload">
          <div class="field">
            <label for="knowledge-file">文本或 Markdown 文件（可多选）</label>
            <input
              id="knowledge-file"
              type="file"
              multiple
              accept=".txt,.md,text/plain,text/markdown"
              @change="pickFiles"
            />
            <p v-if="files.length" class="field-hint">
              已选择 {{ files.length }} 个：{{ files.map((item) => item.name).join("、") }}
            </p>
          </div>
          <button class="button button-primary" :disabled="files.length === 0 || uploadPending">
            {{ uploadPending ? "正在上传…" : `上传 ${files.length || ""}` }}
          </button>
        </form>
        <p v-if="uploadError" class="alert" role="alert">{{ uploadError }}</p>
        <ul v-if="uploadResults.length" class="record-list upload-results">
          <li v-for="result in uploadResults" :key="result.name" class="record">
            <span
              :class="['badge', result.error === null ? 'badge-warning' : 'badge-danger']"
            >{{ result.error === null ? "已受理" : "失败" }}</span>
            <strong>{{ result.name }}</strong>
            <span v-if="result.error === null" class="muted">
              {{ result.status === "READY" ? "处理完成" : "等待后台处理" }}
            </span>
            <span v-if="result.error" class="muted">{{ result.error }}</span>
          </li>
        </ul>
      </section>
      <p v-else-if="selectedProject" class="empty-state">你可阅读和下载项目知识；只有负责人可以上传。</p>
      <section class="panel knowledge-index" aria-labelledby="knowledge-list-title">
        <div class="index-head">
          <div>
            <h2 id="knowledge-list-title" class="panel-title">公共知识文档</h2>
          </div>
        </div>
        <p v-if="loading" class="muted">正在加载知识文档…</p>
        <p v-else-if="documents.length === 0" class="empty-state">该项目还没有公共知识文档。</p>
        <ol v-else class="record-list">
          <li v-for="document in documents" :key="document.id" class="record knowledge-card">
            <div class="record-head knowledge-card-head">
              <h3 class="record-title">{{ document.title }}</h3>
              <span
                class="badge"
                :class="
                  document.status === 'READY'
                    ? 'badge-success'
                    : document.status === 'FAILED'
                      ? 'badge-danger'
                      : 'badge-warning'
                "
              >
                {{ document.status === "READY" ? "可用于审查" : document.status === "FAILED" ? "处理失败" : "正在处理" }}
              </span>
              <span class="knowledge-card-meta">
                {{ document.chunkCount }}/{{ document.embeddedChunkCount }} 向量 Chunk ·
                {{
                  [document.embeddingProvider, document.embeddingModel].filter(Boolean).join(" · ") ||
                  "未记录 Profile"
                }}
              </span>
              <div v-if="projectId !== null" class="record-actions knowledge-card-actions">
                <RouterLink class="button button-quiet" :to="knowledgeDocumentRoute(projectId, document.id)">阅读</RouterLink>
                <a class="button button-quiet" :href="knowledgeDownloadUrl(projectId, document.id)">下载</a>
                <button
                  v-if="isLeader && document.status === 'FAILED'"
                  type="button"
                  class="button button-quiet"
                  :disabled="retryPending"
                  @click="retry(document)"
                >重试处理</button>
                <button
                  v-if="isLeader && document.sourceType === 'PROJECT_KNOWLEDGE'"
                  type="button"
                  class="button button-quiet"
                  :disabled="deletePending"
                  @click="remove(document)"
                >删除文档</button>
              </div>
            </div>
            <p v-if="isLeader && document.sourceType !== 'PROJECT_KNOWLEDGE'" class="field-hint">
              需求 {{ document.sourceRequirementId }} 的附件，请在该需求下解除附件后再删。
            </p>
            <p v-if="document.failureReason" class="alert" role="alert">
              {{ document.failureReason }}
            </p>
            <details class="knowledge-details">
              <summary>索引详情</summary>
              <p class="field-hint">文档分片用于召回相关规范；当前使用精确向量检索，尚未建立数据库向量索引。</p>
              <dl class="meta-list">
                <div><dt>Chunk / 向量</dt><dd>{{ document.chunkCount }} / {{ document.embeddedChunkCount }}</dd></div>
                <div><dt>向量维度</dt><dd>{{ document.embeddingDimension ?? "未就绪" }}</dd></div>
                <div>
                  <dt>Embedding Profile</dt>
                  <dd>
                    {{
                      [document.embeddingProvider, document.embeddingModel, document.embeddingVersion]
                        .filter(Boolean)
                        .join(" · ") || "未记录"
                    }}
                  </dd>
                </div>
                <div><dt>更新时间</dt><dd>{{ formatDateTime(document.updatedAt) }}</dd></div>
              </dl>
            </details>
          </li>
        </ol>
      </section>
    </template>
  </section>
</template>

<style scoped>
.document-content { max-height: 30rem; overflow: auto; white-space: pre-wrap; word-break: break-word; }

.knowledge-summary { display: grid; gap: var(--fp-space-4); grid-template-columns: repeat(4, minmax(0, 1fr)); }
.knowledge-summary .panel { display: grid; gap: var(--fp-space-1); margin-bottom: var(--fp-space-5); padding: var(--fp-space-4); }
.knowledge-summary strong { color: var(--fp-color-accent-inverse); font: 800 1.5rem/1 var(--fp-font-mono); }
.knowledge-summary span { color: var(--fp-color-text-muted); font-size: 0.75rem; }
.vector-metric { border-color: var(--fp-color-info); background: linear-gradient(145deg, var(--fp-color-info-soft), var(--fp-color-surface-glass)); }
.upload-panel { display: grid; align-items: end; gap: var(--fp-space-5); grid-template-columns: minmax(15rem, 0.85fr) minmax(20rem, 1.15fr); }
.upload-panel .inline-form { margin: 0; }
.upload-results { grid-column: 1 / -1; }
.upload-results .record { display: flex; flex-wrap: wrap; align-items: center; gap: var(--fp-space-3); }
.knowledge-index { border-color: var(--fp-color-border-accent); }
/* 一行式文档卡片:标题 + 状态 + 元信息 + 动作右对齐,索引详情折叠在行尾,避免大空白。 */
.knowledge-card-head { align-items: baseline; }
.knowledge-card-meta { flex: 1 1 18rem; min-width: 0; color: var(--fp-color-text-muted); font-size: 0.8125rem; overflow-wrap: anywhere; }
.knowledge-card-actions { margin-top: 0; margin-left: auto; }
.knowledge-details { margin-top: var(--fp-space-3); }
.knowledge-details > summary { color: var(--fp-color-text-muted); cursor: pointer; font-size: 0.8125rem; }
.knowledge-details .meta-list { grid-template-columns: repeat(2, minmax(0, 1fr)); margin-top: var(--fp-space-3); }
@media (max-width: 64rem) {
  .knowledge-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .upload-panel { grid-template-columns: 1fr; }
}
@media (max-width: 42rem) {
  .knowledge-summary { grid-template-columns: 1fr; }
}
</style>
