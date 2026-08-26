<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { parseId, PROJECT_QUERY_KEY } from "../../app/routes";
import { formatDateTime } from "../../lib/datetime";
import { apiErrorMessage } from "../../lib/http";
import { hasProjectRole, listProjects, type Project } from "../project/api";
import {
  deleteProjectKnowledge,
  listProjectKnowledge,
  uploadProjectKnowledge,
  type KnowledgeDocument,
} from "./api";

const route = useRoute();
const router = useRouter();
const projectId = computed(() => parseId(route.query[PROJECT_QUERY_KEY]));
const projects = ref<Project[]>([]);
const documents = ref<KnowledgeDocument[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const files = ref<File[]>([]);
const uploadPending = ref(false);
const uploadError = ref<string | null>(null);
/** 逐文件结果：批量上传是 N 次独立调用，因此每个文件各有自己的成败。 */
const uploadResults = ref<Array<{ name: string; error: string | null }>>([]);
const deletePending = ref(false);
const selectedProject = computed(
  () => projects.value.find((project) => project.id === projectId.value) ?? null,
);
const isLeader = computed(() => hasProjectRole(selectedProject.value, "LEADER"));
const summary = computed(() => ({
  ready: documents.value.filter((document) => document.status === "READY").length,
  chunks: documents.value.reduce((total, document) => total + document.chunkCount, 0),
  embedded: documents.value.reduce((total, document) => total + document.embeddedChunkCount, 0),
  dimensions: [
    ...new Set(
      documents.value
        .map((document) => document.embeddingDimension)
        .filter((value): value is number => value !== null),
    ),
  ],
}));

function selectProject(event: Event): void {
  const id = parseId((event.target as HTMLSelectElement).value);
  if (id !== null) {
    void router.push({ name: "knowledge", query: { [PROJECT_QUERY_KEY]: String(id) } });
  }
}

async function load(): Promise<void> {
  const id = projectId.value;
  documents.value = [];
  error.value = null;
  if (id === null) return;
  loading.value = true;
  try {
    documents.value = await listProjectKnowledge(id);
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  } finally {
    loading.value = false;
  }
}

function pickFiles(event: Event): void {
  files.value = [...((event.target as HTMLInputElement).files ?? [])];
  uploadResults.value = [];
}

/**
 * 逐文件独立上传，不是一个批量端点。每次调用都是后端自己的一个事务，
 * 因此一个文件失败既不会回滚已经成功的那些，也不会阻断后面的；这也避免了一个
 * 横跨 N 次 embedding 外部调用的长事务。
 */
async function upload(): Promise<void> {
  const id = projectId.value;
  if (id === null || files.value.length === 0) return;
  uploadPending.value = true;
  uploadError.value = null;
  uploadResults.value = [];
  for (const selected of files.value) {
    try {
      await uploadProjectKnowledge(id, { title: selected.name, text: await selected.text() });
      uploadResults.value.push({ name: selected.name, error: null });
    } catch (failure: unknown) {
      uploadResults.value.push({ name: selected.name, error: apiErrorMessage(failure) });
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
    await load();
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  } finally {
    deletePending.value = false;
  }
}

onMounted(async () => {
  try {
    projects.value = await listProjects();
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  }
});
watch(projectId, load, { immediate: true });
</script>

<template>
  <section aria-labelledby="knowledge-title">
    <div class="page-head knowledge-hero">
      <div>
        <p class="eyebrow">Semantic project context</p>
        <h1 id="knowledge-title">项目知识</h1>
        <p class="lede">
          文本和 Markdown 被切片并生成 Embedding；当前检索使用精确余弦顺序扫描，不建立向量索引，
          也不暴露原始向量。
        </p>
      </div>
      <div class="vector-visual" aria-label="向量检索处理流程">
        <span>Document</span><i aria-hidden="true">→</i><span>Chunk</span><i aria-hidden="true">→</i
        ><strong>Vector</strong>
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
    <p v-if="projectId === null" class="empty-state">选择一个项目以查看语义知识检索。</p>
    <template v-else>
      <p v-if="error" class="alert" role="alert">{{ error }}</p>
      <section class="knowledge-summary" aria-label="语义检索汇总">
        <div class="panel"><strong>{{ documents.length }}</strong><span>知识文档</span></div>
        <div class="panel">
          <strong>{{ summary.ready }}/{{ documents.length }}</strong><span>Embedding 已就绪</span>
        </div>
        <div class="panel vector-metric">
          <strong>{{ summary.embedded }}/{{ summary.chunks }}</strong><span>Embedding Chunk</span>
        </div>
        <div class="panel vector-metric">
          <strong>{{ summary.dimensions.join(" / ") || "—" }}</strong><span>向量维度</span>
        </div>
      </section>
      <section v-if="isLeader" class="panel upload-panel" aria-labelledby="knowledge-upload-title">
        <div>
          <p class="eyebrow">Build retrieval context</p>
          <h2 id="knowledge-upload-title" class="panel-title">上传项目知识</h2>
          <p class="field-hint">
            支持 .txt 与 .md，可一次选择多个文件。逐个文件独立处理并各自报告结果，
            某个文件失败不影响其余文件。
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
            {{ uploadPending ? "正在处理…" : `上传并处理 ${files.length || ""}` }}
          </button>
        </form>
        <p v-if="uploadError" class="alert" role="alert">{{ uploadError }}</p>
        <ul v-if="uploadResults.length" class="record-list upload-results">
          <li v-for="result in uploadResults" :key="result.name" class="record">
            <span
              :class="['badge', result.error === null ? 'badge-success' : 'badge-danger']"
            >{{ result.error === null ? "成功" : "失败" }}</span>
            <strong>{{ result.name }}</strong>
            <span v-if="result.error" class="muted">{{ result.error }}</span>
          </li>
        </ul>
      </section>
      <p v-else-if="selectedProject" class="empty-state">你可查看项目知识及其检索状态；只有负责人可以上传。</p>
      <section class="panel knowledge-index" aria-labelledby="knowledge-list-title">
        <div class="index-head">
          <div>
            <p class="eyebrow">Vector retrieval profile</p>
            <h2 id="knowledge-list-title" class="panel-title">公共知识文档</h2>
          </div>
          <span class="badge badge-info">无向量索引 · 顺序扫描</span>
        </div>
        <p v-if="loading" class="muted">正在加载知识文档…</p>
        <p v-else-if="documents.length === 0" class="empty-state">该项目还没有公共知识文档。</p>
        <ol v-else class="record-list">
          <li v-for="document in documents" :key="document.id" class="record">
            <div class="record-head">
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
                {{ document.status }}
              </span>
            </div>
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
            <p v-if="document.failureReason" class="alert" role="alert">
              {{ document.failureReason }}
            </p>
            <div v-if="isLeader" class="record-actions">
              <button
                v-if="document.sourceType === 'PROJECT_KNOWLEDGE'"
                type="button"
                class="button button-quiet"
                :disabled="deletePending"
                @click="remove(document)"
              >删除文档</button>
              <span v-else class="field-hint">
                需求 {{ document.sourceRequirementId }} 的附件，请在该需求下解除附件后再删。
              </span>
            </div>
          </li>
        </ol>
      </section>
    </template>
  </section>
</template>

<style scoped>
.knowledge-summary { display: grid; gap: var(--fp-space-4); grid-template-columns: repeat(4, minmax(0, 1fr)); }
.knowledge-summary .panel { display: grid; gap: var(--fp-space-2); margin-bottom: var(--fp-space-6); }
.knowledge-summary strong { color: var(--fp-color-accent-inverse); font: 800 1.5rem/1 var(--fp-font-mono); }
.knowledge-summary span { color: var(--fp-color-text-muted); font-size: 0.75rem; }
.knowledge-hero { display: grid; align-items: center; gap: var(--fp-space-8); grid-template-columns: minmax(0, 1fr) auto; }
.vector-visual { display: flex; align-items: center; gap: var(--fp-space-3); padding: var(--fp-space-5); border: 0.0625rem solid var(--fp-color-border-accent); border-radius: var(--fp-radius-lg); background: var(--fp-color-accent-soft); color: var(--fp-color-text-muted); font: 700 0.6875rem/1 var(--fp-font-mono); text-transform: uppercase; }
.vector-visual i { color: var(--fp-color-accent); font-style: normal; }
.vector-visual strong { color: var(--fp-color-accent-inverse); }
.vector-metric { border-color: var(--fp-color-info); background: linear-gradient(145deg, var(--fp-color-info-soft), var(--fp-color-surface-glass)); }
.upload-panel { display: grid; align-items: end; gap: var(--fp-space-6); grid-template-columns: minmax(15rem, 0.75fr) minmax(20rem, 1.25fr); }
.upload-panel .inline-form { margin: 0; }
.upload-results { grid-column: 1 / -1; }
.upload-results .record { display: flex; flex-wrap: wrap; align-items: center; gap: var(--fp-space-3); }
.knowledge-index { border-color: var(--fp-color-border-accent); }
@media (max-width: 64rem) {
  .knowledge-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .upload-panel { grid-template-columns: 1fr; }
}
@media (max-width: 42rem) {
  .knowledge-hero,
  .knowledge-summary { grid-template-columns: 1fr; }
  .vector-visual { justify-content: center; width: 100%; }
}
</style>
