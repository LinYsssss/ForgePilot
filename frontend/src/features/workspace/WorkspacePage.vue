<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import {
  knowledgeRoute,
  parseId,
  projectMembersRoute,
  PROJECT_QUERY_KEY,
  repositoriesRoute,
  requirementsRoute,
  reviewDetailRoute,
  reviewsRoute,
} from "../../app/routes";
import { formatDateTime } from "../../lib/datetime";
import { apiErrorMessage } from "../../lib/http";
import { listProjectKnowledge, type KnowledgeDocument } from "../knowledge/api";
import { listProjects, type Project } from "../project/api";
import { listRequirements, type RequirementSummary } from "../requirement/api";
import {
  listProjectReviews,
  listReviewActivity,
  type ActivityView,
  type ProjectReviewRow,
} from "../review/api";
import { REVIEW_ACTIVITY_LABELS } from "../review/labels";
import { listScmRepositories, type ScmRepository } from "../scm/api";

const route = useRoute();
const router = useRouter();
const projectId = computed(() => parseId(route.query[PROJECT_QUERY_KEY]));
const projects = ref<Project[]>([]);
const requirements = ref<RequirementSummary[]>([]);
const activity = ref<Record<string, ActivityView>>({});
const reviews = ref<ProjectReviewRow[]>([]);
const knowledge = ref<KnowledgeDocument[]>([]);
const repositories = ref<ScmRepository[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);

const selected = computed(
  () => projects.value.find((project) => project.id === projectId.value) ?? null,
);
const embeddedChunks = computed(() =>
  knowledge.value.reduce((total, document) => total + document.embeddedChunkCount, 0),
);
const readyKnowledge = computed(
  () => knowledge.value.filter((document) => document.status === "READY").length,
);
const requirementStatus = computed(() =>
  requirements.value.reduce<Record<string, number>>(
    (counts, requirement) => ({
      ...counts,
      [requirement.status]: (counts[requirement.status] ?? 0) + 1,
    }),
    {},
  ),
);
const activityDistribution = computed(() =>
  Object.values(activity.value).reduce<Record<string, number>>(
    (counts, item) => ({
      ...counts,
      [item.activity]: (counts[item.activity] ?? 0) + 1,
    }),
    {},
  ),
);

function selectProject(event: Event): void {
  const id = parseId((event.target as HTMLSelectElement).value);
  if (id !== null) {
    void router.push({ name: "workspace", query: { [PROJECT_QUERY_KEY]: String(id) } });
  }
}

async function load(): Promise<void> {
  const id = projectId.value;
  requirements.value = [];
  activity.value = {};
  reviews.value = [];
  knowledge.value = [];
  repositories.value = [];
  error.value = null;
  if (id === null) return;
  loading.value = true;
  try {
    [requirements.value, activity.value, reviews.value, knowledge.value, repositories.value] =
      await Promise.all([
        listRequirements(id),
        listReviewActivity(id),
        listProjectReviews(id),
        listProjectKnowledge(id),
        listScmRepositories(id),
      ]);
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  } finally {
    loading.value = false;
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
  <section aria-labelledby="workspace-title">
    <div class="page-head workspace-hero">
      <div>
        <p class="eyebrow">Project workbench</p>
        <h1 id="workspace-title">工作台</h1>
        <p class="lede">
          用真实项目数据串起需求质量、知识增强实现建议与唯一 AI 审查引擎；AI
          提供可核验的证据与建议，业务状态和最终决定始终由人完成。
        </p>
      </div>
      <div class="hero-signal" aria-label="AI 研发辅助链路在线">
        <span class="signal-orbit" aria-hidden="true"></span>
        <strong>AI + Vector</strong>
        <small>需求驱动 · 知识增强 · 人工闭环</small>
      </div>
    </div>

    <section class="panel project-selector">
      <div>
        <h2 class="panel-title">当前项目</h2>
        <p class="field-hint">选择项目后，统一查看它的需求、知识、仓库与审查状态。</p>
      </div>
      <div class="field">
        <label for="workspace-project">项目</label>
        <select id="workspace-project" :value="projectId ?? ''" @change="selectProject">
          <option value="" disabled>请选择项目</option>
          <option v-for="project in projects" :key="project.id" :value="project.id">
            {{ project.name }}
          </option>
        </select>
      </div>
    </section>

    <p v-if="projectId === null" class="empty-state">选择一个项目以查看研发上下文。</p>
    <template v-else>
      <p v-if="error" class="alert" role="alert">{{ error }}</p>
      <p v-else-if="loading" class="muted">正在汇聚项目工作台…</p>
      <template v-else>
        <section class="ai-section" aria-labelledby="ai-chain-title">
          <div class="section-heading">
            <div>
              <p class="eyebrow">AI delivery loop</p>
              <h2 id="ai-chain-title">AI 辅助研发链路</h2>
            </div>
            <span class="badge badge-info">核心能力</span>
          </div>
          <div class="ai-chain">
            <article class="panel ai-step">
              <span class="ai-step-index">01</span>
              <p class="eyebrow">Requirement Quality</p>
              <h3>需求质量检查</h3>
              <p>以需求正文与验收标准为输入，生成规则发现和结构化改进建议。</p>
              <RouterLink class="button button-quiet" :to="requirementsRoute(projectId)">
                进入需求
              </RouterLink>
            </article>
            <article class="panel ai-step ai-step-featured">
              <span class="ai-step-index">02</span>
              <p class="eyebrow">Knowledge-enhanced</p>
              <h3>向量增强实现建议</h3>
              <p>语义召回项目知识与需求附件，生成实现清单、相关规则和风险。</p>
              <RouterLink class="button button-primary" :to="knowledgeRoute(projectId)">
                查看向量知识
              </RouterLink>
            </article>
            <article class="panel ai-step">
              <span class="ai-step-index">03</span>
              <p class="eyebrow">Single Review Engine</p>
              <h3>AI 代码审查</h3>
              <p>结合 Requirement、AC、语义知识与 Diff，输出可核验 Finding。</p>
              <RouterLink class="button button-quiet" :to="reviewsRoute(projectId)">
                进入审查
              </RouterLink>
            </article>
          </div>
        </section>

        <section class="pulse-grid" aria-label="项目脉搏">
          <div class="panel pulse-card">
            <strong>{{ requirements.length }}</strong>
            <span>研发需求</span>
            <small>
              {{
                Object.entries(requirementStatus)
                  .map(([status, count]) => `${status} ${count}`)
                  .join(" · ") || "暂无"
              }}
            </small>
          </div>
          <div class="panel pulse-card">
            <strong>{{ reviews.length }}</strong>
            <span>审查记录</span>
            <small>
              {{
                Object.entries(activityDistribution)
                  .map(
                    ([status, count]) =>
                      `${REVIEW_ACTIVITY_LABELS[status as keyof typeof REVIEW_ACTIVITY_LABELS]} ${count}`,
                  )
                  .join(" · ") || "暂无审查活动"
              }}
            </small>
          </div>
          <div class="panel pulse-card vector-card">
            <strong>{{ embeddedChunks }}</strong>
            <span>Embedding Chunk</span>
            <small>{{ readyKnowledge }}/{{ knowledge.length }} 文档已就绪</small>
          </div>
          <div class="panel pulse-card">
            <strong>{{ repositories.length }}</strong>
            <span>SCM 仓库</span>
            <small>{{ repositories[0]?.provider ?? "尚未接入" }}</small>
          </div>
        </section>

        <div class="workspace-grid">
          <section class="panel workspace-list-panel">
            <div class="index-head">
              <div>
                <h2 class="panel-title">最近需求</h2>
                <p class="field-hint">状态与派生审查活动分别呈现。</p>
              </div>
              <RouterLink class="button button-quiet" :to="requirementsRoute(projectId)">
                全部需求
              </RouterLink>
            </div>
            <p v-if="requirements.length === 0" class="empty-state">还没有需求。</p>
            <ol v-else class="record-list">
              <li
                v-for="requirement in requirements.slice(0, 5)"
                :key="requirement.id"
                class="record"
              >
                <RouterLink
                  :to="{
                    name: 'requirement-detail',
                    params: { id: String(requirement.id) },
                    query: { project: String(projectId) },
                  }"
                >
                  <strong>{{ requirement.title }}</strong>
                </RouterLink>
                <p class="muted">
                  {{ requirement.status }} ·
                  {{ activity[String(requirement.id)]?.activity ?? "未返回审查活动" }} ·
                  {{ formatDateTime(requirement.updatedAt) }}
                </p>
              </li>
            </ol>
          </section>

          <section class="panel workspace-list-panel">
            <div class="index-head">
              <div>
                <h2 class="panel-title">最近审查</h2>
                <p class="field-hint">单一 AI Review Engine 的执行记录。</p>
              </div>
              <RouterLink class="button button-quiet" :to="reviewsRoute(projectId)">
                全部审查
              </RouterLink>
            </div>
            <p v-if="reviews.length === 0" class="empty-state">还没有 Review。</p>
            <ol v-else class="record-list">
              <li v-for="review in reviews.slice(0, 5)" :key="review.id" class="record">
                <RouterLink :to="reviewDetailRoute(projectId, review.id)">
                  <strong>Review {{ review.id }} · PR #{{ review.pullRequestNumber }}</strong>
                </RouterLink>
                <p class="muted">
                  {{ review.status }} · Decision {{ review.decision }} ·
                  {{ formatDateTime(review.createdAt) }}
                </p>
              </li>
            </ol>
          </section>
        </div>

        <section v-if="selected" class="panel shortcuts">
          <div>
            <h2 class="panel-title">项目快捷入口</h2>
            <p class="field-hint">继续管理当前项目的协作和上下文。</p>
          </div>
          <div class="shortcut-actions">
            <RouterLink class="button" :to="projectMembersRoute(selected.id)">成员</RouterLink>
            <RouterLink class="button" :to="repositoriesRoute(selected.id)">仓库接入</RouterLink>
            <RouterLink class="button" :to="knowledgeRoute(selected.id)">项目知识</RouterLink>
          </div>
        </section>
      </template>
    </template>
  </section>
</template>
<style scoped>
.workspace-hero {
  display: grid;
  align-items: center;
  gap: var(--fp-space-8);
  grid-template-columns: minmax(0, 1fr) auto;
}

.hero-signal {
  position: relative;
  display: grid;
  width: 15rem;
  min-height: 9rem;
  place-content: center;
  overflow: hidden;
  border: 0.0625rem solid var(--fp-color-border-accent);
  border-radius: var(--fp-radius-lg);
  background: var(--fp-color-accent-soft);
  text-align: center;
}

.hero-signal strong {
  color: var(--fp-color-accent-inverse);
  font: 800 1rem/1 var(--fp-font-mono);
  letter-spacing: 0.06em;
}

.hero-signal small {
  margin-top: var(--fp-space-3);
  color: var(--fp-color-text-muted);
}

.signal-orbit {
  position: absolute;
  inset: 1rem;
  border: 0.0625rem solid var(--fp-color-border-accent);
  border-radius: 50%;
  animation: fp-pulse-glow var(--fp-duration-pulse) ease-in-out infinite;
}

.ai-section {
  margin-bottom: var(--fp-space-6);
}

.section-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--fp-space-4);
  margin-bottom: var(--fp-space-5);
}

.section-heading h2 {
  margin: 0;
}

.ai-chain,
.pulse-grid,
.workspace-grid {
  display: grid;
  gap: var(--fp-space-5);
}

.ai-chain {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.ai-step {
  display: flex;
  min-height: 18rem;
  margin-bottom: 0;
  flex-direction: column;
  border-color: var(--fp-color-border-accent);
}

.ai-step-featured {
  background: linear-gradient(145deg, var(--fp-color-accent-soft), var(--fp-color-surface-glass));
  box-shadow: var(--fp-shadow-elevated), var(--fp-shadow-accent);
}

.ai-step-index {
  align-self: flex-end;
  color: var(--fp-color-text-subtle);
  font: 800 1.75rem/1 var(--fp-font-mono);
}

.ai-step h3 {
  margin: 0;
  font-size: 1.125rem;
}

.ai-step p:not(.eyebrow) {
  color: var(--fp-color-text-muted);
  line-height: 1.65;
}

.ai-step .button {
  align-self: flex-start;
  margin-top: auto;
}

.pulse-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.pulse-card {
  display: grid;
  gap: var(--fp-space-2);
  margin-bottom: var(--fp-space-6);
}

.pulse-card strong {
  color: var(--fp-color-accent-inverse);
  font: 800 1.7rem/1 var(--fp-font-mono);
}

.pulse-card span {
  font-weight: 700;
}

.pulse-card small {
  color: var(--fp-color-text-muted);
}

.vector-card {
  border-color: var(--fp-color-info);
  background: linear-gradient(145deg, var(--fp-color-info-soft), var(--fp-color-surface-glass));
}

.workspace-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.workspace-list-panel {
  min-width: 0;
}

.shortcuts {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--fp-space-4);
}

.shortcuts .panel-title {
  margin-bottom: var(--fp-space-1);
}

.shortcut-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fp-space-3);
}

@media (max-width: 64rem) {
  .ai-chain,
  .pulse-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .ai-step-featured {
    grid-column: span 1;
  }
}

@media (max-width: 42rem) {
  .workspace-hero,
  .ai-chain,
  .pulse-grid,
  .workspace-grid {
    grid-template-columns: 1fr;
  }

  .hero-signal {
    width: 100%;
  }

  .section-heading,
  .shortcuts {
    align-items: flex-start;
    flex-direction: column;
  }

  .shortcut-actions,
  .shortcut-actions .button {
    width: 100%;
  }
}
</style>
