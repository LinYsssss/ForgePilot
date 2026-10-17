<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink, type RouteLocationRaw } from "vue-router";
import {
  knowledgeRoute,
  parseId,
  projectMembersRoute,
  repositoriesRoute,
  requirementDetailRoute,
  requirementsRoute,
  reviewDetailRoute,
  reviewsRoute,
} from "../../app/routes";
import { useProjectSelection } from "../../composables/useProjectSelection";
import { formatRelativeDateTime } from "../../lib/datetime";
import { apiErrorMessage } from "../../lib/http";
import { useSession } from "../auth/session";
import { listProjectKnowledge, type KnowledgeDocument } from "../knowledge/api";
import { listMembers, PROJECT_ROLE_LABELS, type Member } from "../project/api";
import { requirementPhase, REQUIREMENT_STATUS_TONES } from "../requirement/status";
import { listRequirements, type RequirementSummary } from "../requirement/api";
import {
  getReviewCalibration,
  listProjectReviews,
  listReviewActivity,
  type ActivityView,
  type ProjectReviewRow,
  type ReviewCalibration,
} from "../review/api";
import {
  FINDING_CONFIDENCE_LABELS,
  pullRequestLabel,
  REVIEW_STATUS_LABELS,
  REVIEW_STATUS_TONES,
} from "../review/labels";
import { listScmRepositories, type ScmRepository } from "../scm/api";

const {
  projects,
  selectedProjectId: projectId,
  selectedProject: selected,
  error,
  select,
} = useProjectSelection({ routeName: "workspace", autoSelectFirst: true });
const { account } = useSession();

const requirements = ref<RequirementSummary[]>([]);
const activity = ref<Record<string, ActivityView>>({});
const reviews = ref<ProjectReviewRow[]>([]);
const knowledge = ref<KnowledgeDocument[]>([]);
const repositories = ref<ScmRepository[]>([]);
const members = ref<Member[]>([]);
const calibration = ref<ReviewCalibration | null>(null);
const loading = ref(false);

const queueTab = ref<QueueKind | "ALL">("ALL");
const onlyMine = ref(false);

type QueueKind = "DEV" | "REVIEW" | "REQ";

const QUEUE_KIND_LABELS: Record<QueueKind, string> = {
  DEV: "开发任务",
  REVIEW: "审查任务",
  REQ: "需求确认",
};

/**
 * 待办队列的一行。三类任务(开发/审查/需求)在这里拼成同一种形状，
 * 但行与行之间的差别保留在这个结构里：徽标说的是行自己的状态，
 * `reason` 说的是"为什么现在轮到它"，而动作永远只有一个主要入口。
 */
interface WorkItem {
  key: string;
  kind: QueueKind;
  badgeLabel: string;
  badgeTone: string;
  title: string;
  titleRoute: RouteLocationRaw;
  reason: string;
  mine: boolean;
  updatedAt: string;
  actionLabel: string;
  actionRoute: RouteLocationRaw;
}

const me = computed(() => account.value?.id ?? null);
const isLeader = computed(() => selected.value?.myRoles.includes("LEADER") === true);
const myRoleNames = computed(
  () => selected.value?.myRoles.map((role) => PROJECT_ROLE_LABELS[role]).join("、") || "—",
);

/**
 * 把一条需求折叠成待办行。终态需求不成行；「开发中」的徽标与原因
 * 取自评审活动的派生量，两者刻意分开写：状态由人维护，活动是只读派生。
 */
function requirementItem(requirement: RequirementSummary): WorkItem | null {
  const state = activity.value[String(requirement.id)]?.activity ?? null;
  const mine =
    requirement.assigneeId === me.value ||
    requirement.reviewerId === me.value ||
    (requirement.reviewerId === null && isLeader.value);
  const base = {
    key: `requirement-${requirement.id}`,
    title: requirement.title,
    titleRoute: requirementDetailRoute(projectId.value ?? 0, requirement.id),
    mine,
    updatedAt: requirement.updatedAt,
  };
  switch (requirement.status) {
    case "DRAFT":
      return {
        ...base,
        kind: "REQ",
        badgeLabel: "草稿",
        badgeTone: "neutral",
        reason: "草稿待完善，完善后置为「就绪」进入评审",
        actionLabel: "查看需求",
        actionRoute: base.titleRoute,
      };
    case "READY":
      return {
        ...base,
        kind: "REQ",
        badgeLabel: "待指派",
        badgeTone: "info",
        reason: "需求已就绪，等待负责人指派开发",
        actionLabel: "查看需求",
        actionRoute: base.titleRoute,
      };
    case "IN_DEVELOPMENT": {
      const reviewIndex = reviewsRoute(projectId.value ?? 0);
      switch (state) {
        case "CHANGES_REQUESTED":
          return {
            ...base,
            kind: "DEV",
            badgeLabel: "退回修改",
            badgeTone: "danger",
            reason: "审查已退回，需修复问题后提交新代码复审",
            actionLabel: "进入审查",
            actionRoute: reviewIndex,
          };
        case "FAILED":
          return {
            ...base,
            kind: "DEV",
            badgeLabel: "审查失败",
            badgeTone: "danger",
            reason: "AI 审查执行失败，可进入审查页重试",
            actionLabel: "进入审查",
            actionRoute: reviewIndex,
          };
        case "REVIEW_REQUIRED":
          return {
            ...base,
            kind: "DEV",
            badgeLabel: "待审查",
            badgeTone: "warning",
            reason: "已有 PR 关联到该需求，尚未发起审查",
            actionLabel: "进入审查",
            actionRoute: reviewIndex,
          };
        case "PENDING":
          return {
            ...base,
            kind: "DEV",
            badgeLabel: "排队中",
            badgeTone: "neutral",
            reason: "审查已排队，等待开始分析",
            actionLabel: "进入审查",
            actionRoute: reviewIndex,
          };
        case "REVIEWING":
          return {
            ...base,
            kind: "DEV",
            badgeLabel: "审查中",
            badgeTone: "info",
            reason: "AI 正在分析变更，完成后进入人工审查",
            actionLabel: "进入审查",
            actionRoute: reviewIndex,
          };
        case "APPROVED":
          return {
            ...base,
            kind: "DEV",
            badgeLabel: "待确认完成",
            badgeTone: "success",
            reason: "全部关联审查均已通过，待负责人确认完成",
            actionLabel: "查看需求",
            actionRoute: base.titleRoute,
          };
        case "MIXED":
          return {
            ...base,
            kind: "DEV",
            badgeLabel: "多 PR 处理中",
            badgeTone: "warning",
            reason: "多个 PR 的审查状态不一致，请逐个核对",
            actionLabel: "进入审查",
            actionRoute: reviewIndex,
          };
        default:
          return {
            ...base,
            kind: "DEV",
            badgeLabel: "开发中",
            badgeTone: "warning",
            reason: "开发中；关联 PR 后可在审查页发起审查",
            actionLabel: "查看需求",
            actionRoute: base.titleRoute,
          };
      }
    }
    case "DONE":
    case "CANCELED":
      return null;
  }
}

/** 当前 head 上尚未裁定的审查、以及失败的审查，才需要人处理。 */
function reviewItem(review: ProjectReviewRow): WorkItem | null {
  if (!review.isCurrent) return null;
  const linked =
    review.requirementId === null
      ? null
      : requirements.value.find((item) => item.id === review.requirementId) ?? null;
  const mine = isLeader.value || (me.value !== null && linked?.reviewerId === me.value);
  const base = {
    key: `review-${review.id}`,
    title: `${pullRequestLabel(review.provider, review.pullRequestNumber)} · ${review.pullRequestTitle}`,
    titleRoute: reviewDetailRoute(projectId.value ?? 0, review.id),
    mine,
    updatedAt: review.createdAt,
  };
  if (review.status === "COMPLETED" && review.decision === "PENDING") {
    return {
      ...base,
      kind: "REVIEW",
      badgeLabel: "待决定",
      badgeTone: "warning",
      reason: "审查已完成，终局决定尚未给出",
      actionLabel: "进入审查",
      actionRoute: base.titleRoute,
    };
  }
  if (review.status === "FAILED") {
    return {
      ...base,
      kind: "REVIEW",
      badgeLabel: "待重试",
      badgeTone: "danger",
      reason: "AI 审查失败，可重新发起",
      actionLabel: "进入审查",
      actionRoute: base.titleRoute,
    };
  }
  return null;
}

const queueRows = computed<WorkItem[]>(() => {
  const items: WorkItem[] = [];
  for (const requirement of requirements.value) {
    const item = requirementItem(requirement);
    if (item !== null) items.push(item);
  }
  for (const review of reviews.value) {
    const item = reviewItem(review);
    if (item !== null) items.push(item);
  }
  return items.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
});

const queueCounts = computed<Record<QueueKind | "ALL", number>>(() => {
  const counts: Record<QueueKind | "ALL", number> = {
    ALL: queueRows.value.length,
    DEV: 0,
    REVIEW: 0,
    REQ: 0,
  };
  for (const item of queueRows.value) counts[item.kind] += 1;
  return counts;
});

const queueTabs = computed(() => [
  { kind: "ALL" as const, label: "全部", count: queueCounts.value.ALL },
  { kind: "DEV" as const, label: "开发任务", count: queueCounts.value.DEV },
  { kind: "REVIEW" as const, label: "审查任务", count: queueCounts.value.REVIEW },
  { kind: "REQ" as const, label: "需求确认", count: queueCounts.value.REQ },
]);

const visibleRows = computed(() =>
  queueRows.value.filter(
    (item) =>
      (queueTab.value === "ALL" || item.kind === queueTab.value) &&
      (!onlyMine.value || item.mine),
  ),
);

const currentReviews = computed(() => reviews.value.filter((review) => review.isCurrent));

const kpis = computed(() => {
  const developing = requirements.value.filter((item) => item.status === "IN_DEVELOPMENT");
  const mineDeveloping = developing.filter((item) => item.assigneeId === me.value).length;
  const undecided = currentReviews.value.filter(
    (review) => review.status === "COMPLETED" && review.decision === "PENDING",
  ).length;
  const queued = currentReviews.value.filter((review) => review.status === "PENDING").length;
  const running = currentReviews.value.filter((review) => review.status === "RUNNING").length;
  const awaitingReview = Object.values(activity.value).reduce(
    (sum, view) => sum + view.counts.REVIEW_REQUIRED,
    0,
  );
  const project = projectId.value ?? 0;
  return [
    {
      key: "developing",
      label: "开发中需求",
      value: developing.length,
      meta: `指派给我 ${mineDeveloping}`,
      to: requirementsRoute(project),
    },
    {
      key: "decision",
      label: "待最终决定",
      value: undecided,
      meta: "审查已完成，尚未裁定",
      to: reviewsRoute(project),
    },
    {
      key: "running",
      label: "进行中的审查",
      value: queued + running,
      meta: `排队中 ${queued} · 审查中 ${running}`,
      to: reviewsRoute(project),
    },
    {
      key: "required",
      label: "待发起审查",
      value: awaitingReview,
      meta: "已关联需求、尚未审查的 PR",
      to: reviewsRoute(project),
    },
  ];
});

const readyKnowledge = computed(
  () => knowledge.value.filter((document) => document.status === "READY").length,
);

/** 项目准备清单：每行给"现在是什么状态"和一个可直接判断的徽标。 */
const readiness = computed(() => {
  const total = knowledge.value.length;
  const failed = knowledge.value.filter((document) => document.status === "FAILED").length;
  const processing = knowledge.value.filter((document) => document.status === "PENDING").length;
  const knowledgeRow =
    total === 0
      ? { label: "知识文档", value: "暂无文档", badge: "待上传", tone: "warning" }
      : failed > 0
        ? {
            label: "知识文档",
            value: `${readyKnowledge.value}/${total}`,
            badge: `${failed} 处理失败`,
            tone: "danger",
          }
        : processing > 0
          ? {
              label: "知识文档",
              value: `${readyKnowledge.value}/${total}`,
              badge: `${processing} 处理中`,
              tone: "warning",
            }
          : { label: "知识文档", value: `${total}/${total}`, badge: "已就绪", tone: "success" };
  const repository = repositories.value[0] ?? null;
  const repositoryRow =
    repository === null
      ? { label: "代码仓库", value: "未接入", badge: "待接入", tone: "warning" }
      : {
          label: "代码仓库",
          value: repository.provider === "GITLAB" ? "GitLab" : "GitHub",
          badge: "已接入",
          tone: "success",
        };
  const leader = members.value.find((member) => member.roles.includes("LEADER")) ?? null;
  const memberRow = {
    label: "项目成员",
    value: `${members.value.length} 人`,
    badge: leader === null ? "无负责人" : `负责人 ${leader.displayName}`,
    tone: leader === null ? "warning" : "neutral",
  };
  return [knowledgeRow, repositoryRow, memberRow];
});

const runningReviews = computed(() =>
  currentReviews.value
    .filter((review) => review.status === "PENDING" || review.status === "RUNNING")
    .slice(0, 3),
);

/**
 * 最近动态由既有行派生：审查行的时刻是"发起时间"，需求行的时刻是
 * "更新时间"，文案如实说出这一点；不虚构完成时刻，也就不需要新接口。
 */
const feed = computed(() => {
  const events = [
    ...currentReviews.value.map((review) => ({
      key: `review-${review.id}`,
      time: review.createdAt,
      text: `发起审查：${pullRequestLabel(review.provider, review.pullRequestNumber)} · ${review.pullRequestTitle}`,
      badgeLabel: REVIEW_STATUS_LABELS[review.status],
      badgeTone: REVIEW_STATUS_TONES[review.status],
    })),
    ...requirements.value.map((requirement) => ({
      key: `requirement-${requirement.id}`,
      time: requirement.updatedAt,
      text: `更新需求：${requirement.title}`,
      badgeLabel: requirementPhase(
        requirement.status,
        activity.value[String(requirement.id)]?.activity,
      ),
      badgeTone: REQUIREMENT_STATUS_TONES[requirement.status],
    })),
  ];
  return events.sort((a, b) => b.time.localeCompare(a.time)).slice(0, 5);
});

/**
 * 把一个 0..1 的比例格式化成百分比。`null` 是「没有样本」，不是 0%——
 * 这两件事在校准表上导向相反的结论，因此这里绝不把 null 折成一个数。
 */
function percent(value: number | null): string {
  return value === null ? "—" : `${(value * 100).toFixed(0)}%`;
}

function selectProject(event: Event): void {
  void select(parseId((event.target as HTMLSelectElement).value));
}

async function load(): Promise<void> {
  const id = projectId.value;
  requirements.value = [];
  activity.value = {};
  reviews.value = [];
  knowledge.value = [];
  repositories.value = [];
  members.value = [];
  calibration.value = null;
  error.value = null;
  if (id === null) return;
  loading.value = true;
  try {
    [
      requirements.value,
      activity.value,
      reviews.value,
      knowledge.value,
      repositories.value,
      members.value,
      calibration.value,
    ] = await Promise.all([
      listRequirements(id),
      listReviewActivity(id),
      listProjectReviews(id),
      listProjectKnowledge(id),
      listScmRepositories(id),
      listMembers(id),
      // 同上：校准卡失败只是它自己不显示，不该让整个工作台变成加载失败。
      getReviewCalibration(id).catch(() => null),
    ]);
  } catch (failure: unknown) {
    error.value = apiErrorMessage(failure);
  } finally {
    loading.value = false;
  }
}

watch(projectId, load, { immediate: true });
</script>

<template>
  <section aria-labelledby="workspace-title">
    <div class="page-head workbench-head">
      <div>
        <h1 id="workspace-title">工作台</h1>
        <p class="workbench-context">
          <template v-if="selected">
            {{ selected.name }} · 我的角色：{{ myRoleNames }}
          </template>
          <template v-else>当前项目进展与分配给我的工作。</template>
        </p>
      </div>
      <div class="workbench-head-actions">
        <select aria-label="选择项目" :value="projectId ?? ''" @change="selectProject">
          <option value="" disabled>请选择项目</option>
          <option v-for="project in projects" :key="project.id" :value="project.id">
            {{ project.name }}
          </option>
        </select>
        <RouterLink
          v-if="projectId !== null"
          class="button button-primary"
          :to="reviewsRoute(projectId)"
        >
          发起代码审查
        </RouterLink>
      </div>
    </div>

    <template v-if="projectId === null">
      <p v-if="error" class="alert" role="alert">{{ error }}</p>
      <section v-else-if="projects.length === 0" class="panel empty-guide">
        <h2 class="panel-title">先建一个项目</h2>
        <p class="field-hint">工作台围绕项目聚合需求、知识、仓库与审查。</p>
        <RouterLink class="button button-primary" :to="{ name: 'projects' }">
          去新建项目
        </RouterLink>
      </section>
      <p v-else class="empty-state">正在进入默认项目…</p>
    </template>
    <template v-else>
      <p v-if="error" class="alert" role="alert">{{ error }}</p>
      <p v-else-if="loading" class="muted">正在加载工作台…</p>
      <template v-else>
        <section class="pulse-grid workbench-kpis" aria-label="项目计数">
          <RouterLink
            v-for="kpi in kpis"
            :key="kpi.key"
            class="panel pulse-card kpi-card"
            :to="kpi.to"
          >
            <strong>{{ kpi.value }}</strong>
            <span>{{ kpi.label }}</span>
            <small>{{ kpi.meta }}</small>
          </RouterLink>
        </section>

        <div class="workbench-columns">
          <section class="panel workbench-queue" aria-labelledby="queue-title">
            <div class="queue-head">
              <h2 id="queue-title" class="panel-title">我的待办</h2>
              <label class="queue-mine-toggle">
                <input v-model="onlyMine" type="checkbox" />
                只看待我处理
              </label>
            </div>
            <div class="queue-tabs" role="group" aria-label="按任务类型筛选">
              <button
                v-for="tab in queueTabs"
                :key="tab.kind"
                type="button"
                class="button button-quiet queue-tab"
                :class="{ 'queue-tab-active': queueTab === tab.kind }"
                :aria-pressed="queueTab === tab.kind"
                @click="queueTab = tab.kind"
              >
                {{ tab.label }}
                <span class="queue-count">{{ tab.count }}</span>
              </button>
            </div>
            <p v-if="visibleRows.length === 0" class="empty-state">
              {{ onlyMine ? "没有需要我处理的任务。" : "没有待处理的任务。" }}
            </p>
            <div v-else class="table-scroll queue-scroll">
              <table class="data-table queue-table">
                <thead>
                  <tr>
                    <th scope="col">任务</th>
                    <th scope="col">类型</th>
                    <th scope="col">需要处理的原因</th>
                    <th scope="col">更新时间</th>
                    <th scope="col">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="item in visibleRows" :key="item.key">
                    <td>
                      <span class="queue-task">
                        <span :class="['badge', `badge-${item.badgeTone}`]">
                          {{ item.badgeLabel }}
                        </span>
                        <RouterLink class="queue-title-link" :to="item.titleRoute">
                          {{ item.title }}
                        </RouterLink>
                      </span>
                    </td>
                    <td>{{ QUEUE_KIND_LABELS[item.kind] }}</td>
                    <td class="queue-reason">{{ item.reason }}</td>
                    <td>{{ formatRelativeDateTime(item.updatedAt) }}</td>
                    <td>
                      <RouterLink class="button button-quiet" :to="item.actionRoute">
                        {{ item.actionLabel }}
                      </RouterLink>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p v-if="visibleRows.length > 0" class="queue-foot">
              <span>共 {{ visibleRows.length }} 项</span>
              <span class="queue-foot-links">
                <RouterLink :to="requirementsRoute(projectId ?? 0)">全部需求 →</RouterLink>
                <RouterLink :to="reviewsRoute(projectId ?? 0)">全部审查 →</RouterLink>
              </span>
            </p>
          </section>

          <aside class="workbench-rail">
            <section class="panel rail-panel" aria-labelledby="readiness-title">
              <div class="rail-head">
                <h2 id="readiness-title" class="panel-title">项目准备</h2>
                <RouterLink
                  v-if="projectId !== null"
                  class="rail-link"
                  :to="projectMembersRoute(projectId)"
                >
                  项目管理 →
                </RouterLink>
              </div>
              <ul class="readiness-list">
                <li v-for="row in readiness" :key="row.label" class="readiness-row">
                  <span class="readiness-fact">
                    <span class="readiness-label">{{ row.label }}</span>
                    <strong class="readiness-value">{{ row.value }}</strong>
                  </span>
                  <span :class="['badge', `badge-${row.tone}`]">{{ row.badge }}</span>
                </li>
              </ul>
            </section>

            <section class="panel rail-panel" aria-labelledby="running-title">
              <div class="rail-head">
                <h2 id="running-title" class="panel-title">正在进行</h2>
              </div>
              <p v-if="runningReviews.length === 0" class="empty-state">
                没有排队或分析中的审查。
              </p>
              <ol v-else class="running-list">
                <li v-for="review in runningReviews" :key="review.id" class="running-item">
                  <RouterLink :to="reviewDetailRoute(projectId ?? 0, review.id)">
                    {{ pullRequestLabel(review.provider, review.pullRequestNumber) }} ·
                    {{ review.pullRequestTitle }}
                  </RouterLink>
                  <div class="running-meta">
                    <span :class="['badge', `badge-${REVIEW_STATUS_TONES[review.status]}`]">
                      {{ REVIEW_STATUS_LABELS[review.status] }}
                    </span>
                  </div>
                  <div
                    :class="['running-track', { 'running-track-active': review.status === 'RUNNING' }]"
                    aria-hidden="true"
                  >
                    <span></span>
                  </div>
                </li>
              </ol>
            </section>

            <section class="panel rail-panel" aria-labelledby="feed-title">
              <div class="rail-head">
                <h2 id="feed-title" class="panel-title">最近动态</h2>
              </div>
              <p v-if="feed.length === 0" class="empty-state">还没有项目动态。</p>
              <ol v-else class="feed-list">
                <li v-for="event in feed" :key="event.key" class="feed-item">
                  <div class="feed-meta">
                    <time>{{ formatRelativeDateTime(event.time) }}</time>
                    <span :class="['badge', `badge-${event.badgeTone}`]">
                      {{ event.badgeLabel }}
                    </span>
                  </div>
                  <p class="feed-text">{{ event.text }}</p>
                </li>
              </ol>
            </section>

            <section class="panel rail-panel" aria-labelledby="shortcuts-title">
              <div class="rail-head">
                <h2 id="shortcuts-title" class="panel-title">快捷操作</h2>
              </div>
              <div class="shortcut-list">
                <RouterLink class="shortcut-link" :to="requirementsRoute(projectId ?? 0)">
                  新建需求 <span aria-hidden="true">→</span>
                </RouterLink>
                <RouterLink class="shortcut-link" :to="knowledgeRoute(projectId ?? 0)">
                  上传知识 <span aria-hidden="true">→</span>
                </RouterLink>
                <RouterLink class="shortcut-link" :to="repositoriesRoute(projectId ?? 0)">
                  查看仓库 <span aria-hidden="true">→</span>
                </RouterLink>
              </div>
            </section>
          </aside>
        </div>

        <section v-if="calibration" class="panel calibration-panel">
          <div>
            <h2 class="panel-title">模型置信度校准</h2>
            <p class="field-hint">
              模型自报的把握对上人工的首次裁决，不参与任何自动流转；区间为 95% Wilson，
              与正式评测报告同一套口径。
            </p>
          </div>
          <table class="calibration-table">
            <thead>
              <tr>
                <th scope="col">置信度</th>
                <th scope="col">已裁决</th>
                <th scope="col">确认</th>
                <th scope="col">确认率</th>
                <th scope="col">95% 区间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="bin in calibration.bins" :key="bin.confidence">
                <th scope="row">{{ FINDING_CONFIDENCE_LABELS[bin.confidence] }}</th>
                <td>{{ bin.adjudicated }}</td>
                <td>{{ bin.confirmed }}</td>
                <td>{{ percent(bin.confirmedRate) }}</td>
                <td>
                  <span v-if="bin.interval">
                    {{ percent(bin.interval.low) }} – {{ percent(bin.interval.high) }}
                  </span>
                  <span v-else class="calibration-empty">样本不足</span>
                </td>
              </tr>
            </tbody>
          </table>
          <p class="field-hint">
            还有 {{ calibration.awaitingAdjudication }} 条待人工裁决；另有
            {{ calibration.withoutConfidence }} 条产自更早的 prompt 版本、本就没有置信度，
            不计入本表。
          </p>
        </section>
      </template>
    </template>
  </section>
</template>

<style scoped>
.workbench-head {
  display: grid;
  align-items: center;
  gap: var(--fp-space-6);
  grid-template-columns: minmax(0, 1fr) auto;
}

.workbench-context {
  margin: var(--fp-space-2) 0 0;
  color: var(--fp-color-text-muted);
}

.workbench-head-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--fp-space-3);
}

.workbench-head-actions select {
  width: auto;
  min-width: 11rem;
}

.workbench-kpis {
  margin-bottom: var(--fp-space-6);
}

.kpi-card {
  margin-bottom: 0;
  color: inherit;
  text-decoration: none;
  transition: border-color var(--fp-duration-fast) var(--fp-ease-standard);
}

.kpi-card:hover {
  border-color: var(--fp-color-border-accent);
}

.pulse-card {
  display: grid;
  gap: var(--fp-space-2);
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

.pulse-grid {
  display: grid;
  gap: var(--fp-space-5);
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.workbench-columns {
  display: grid;
  align-items: start;
  gap: var(--fp-space-5);
  grid-template-columns: minmax(0, 2.2fr) minmax(0, 1fr);
}

.workbench-queue {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

/* 待办定高内滚,左栏与右栏高度相近;表头吸顶,滚动时列名不丢。 */
.queue-scroll {
  max-height: 30rem;
  overflow: auto;
}

.queue-table th,
.queue-table td {
  padding: var(--fp-space-2) var(--fp-space-3);
}

.queue-table thead th {
  position: sticky;
  z-index: 1;
  top: 0;
  background: var(--fp-color-surface-strong);
  box-shadow: inset 0 -0.0625rem 0 var(--fp-color-border);
}

.queue-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fp-space-3);
  margin: var(--fp-space-3) 0 0;
  color: var(--fp-color-text-muted);
  font-size: 0.75rem;
}

.queue-foot-links {
  display: inline-flex;
  gap: var(--fp-space-4);
}

.queue-foot-links a {
  color: var(--fp-color-accent-inverse);
}

.queue-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fp-space-4);
  margin-bottom: var(--fp-space-4);
}

.queue-head .panel-title {
  margin-bottom: 0;
}

.queue-mine-toggle {
  display: inline-flex;
  align-items: center;
  gap: var(--fp-space-2);
  color: var(--fp-color-text-muted);
  font-size: 0.8125rem;
  cursor: pointer;
  white-space: nowrap;
}

.queue-mine-toggle input {
  width: 1rem;
  min-height: 0;
  height: 1rem;
  accent-color: var(--fp-color-accent);
}

.queue-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fp-space-2);
  margin-bottom: var(--fp-space-4);
}

.queue-tab {
  padding: var(--fp-space-2) var(--fp-space-3);
  font-size: 0.8125rem;
}

.queue-tab-active {
  border-color: var(--fp-color-border-accent);
  background: var(--fp-color-accent-soft);
  color: var(--fp-color-accent-inverse);
}

.queue-count {
  margin-left: var(--fp-space-2);
  color: var(--fp-color-text-subtle);
  font: 700 0.75rem/1 var(--fp-font-mono);
}

.queue-table {
  min-width: 40rem;
}

.queue-table td {
  vertical-align: top;
}

.queue-task {
  display: inline-flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--fp-space-2);
}

.queue-title-link {
  min-width: 0;
  overflow-wrap: anywhere;
}

.queue-reason {
  color: var(--fp-color-text-muted);
}

.queue-table .button {
  white-space: nowrap;
}

.workbench-rail {
  display: grid;
  gap: var(--fp-space-5);
  min-width: 0;
}

.rail-panel {
  margin-bottom: 0;
}

.rail-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--fp-space-3);
  margin-bottom: var(--fp-space-3);
}

.rail-head .panel-title {
  margin-bottom: 0;
}

.rail-link {
  color: var(--fp-color-accent-inverse);
  font-size: 0.8125rem;
}

.readiness-list {
  display: grid;
  gap: var(--fp-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.readiness-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fp-space-3);
}

.readiness-fact {
  display: inline-flex;
  align-items: baseline;
  min-width: 0;
  flex-wrap: wrap;
  gap: var(--fp-space-2);
}

.readiness-label {
  color: var(--fp-color-text-muted);
  font-size: 0.8125rem;
}

.readiness-value {
  color: var(--fp-color-text);
  font: 700 0.875rem/1.4 var(--fp-font-mono);
  white-space: nowrap;
}

.running-list {
  display: grid;
  gap: var(--fp-space-4);
  margin: 0;
  padding: 0;
  list-style: none;
}

.running-item {
  display: grid;
  gap: var(--fp-space-2);
}

.running-item a {
  min-width: 0;
  overflow-wrap: anywhere;
}

.running-track {
  height: 0.375rem;
  overflow: hidden;
  border-radius: var(--fp-radius-full);
  background: var(--fp-color-border-subtle);
}

.running-track span {
  display: none;
  width: 100%;
  height: 100%;
  background: var(--fp-gradient-primary);
}

.running-track-active span {
  display: block;
  animation: fp-workbench-track var(--fp-duration-sweep) var(--fp-ease-cinematic) infinite;
}

@keyframes fp-workbench-track {
  from {
    transform: translateX(-100%);
  }

  to {
    transform: translateX(100%);
  }
}

@media (prefers-reduced-motion: reduce) {
  .running-track-active span {
    animation: none;
    transform: none;
    opacity: 0.6;
  }
}

.feed-list {
  display: grid;
  gap: var(--fp-space-4);
  margin: 0;
  padding: 0;
  list-style: none;
}

.feed-item {
  display: grid;
  gap: var(--fp-space-1);
}

.feed-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fp-space-3);
}

.feed-meta time {
  color: var(--fp-color-text-subtle);
  font-size: 0.75rem;
  white-space: nowrap;
}

.feed-text {
  margin: 0;
  color: var(--fp-color-text-muted);
  font-size: 0.8125rem;
  overflow-wrap: anywhere;
}

.shortcut-list {
  display: grid;
  gap: var(--fp-space-2);
}

.shortcut-link {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fp-space-3);
  padding: var(--fp-space-3) var(--fp-space-4);
  border: 0.0625rem solid var(--fp-color-border);
  border-radius: var(--fp-radius-sm);
  background: var(--fp-color-canvas-muted);
  transition: border-color var(--fp-duration-fast) var(--fp-ease-standard);
}

.shortcut-link:hover {
  border-color: var(--fp-color-border-accent);
}

.empty-guide {
  display: grid;
  justify-items: start;
  gap: var(--fp-space-3);
}

.calibration-table {
  width: 100%;
  margin-top: var(--fp-space-4);
  border-collapse: collapse;
  font-size: 0.875rem;
}

.calibration-table th,
.calibration-table td {
  padding: var(--fp-space-2) var(--fp-space-3);
  text-align: left;
  border-bottom: 0.0625rem solid var(--fp-color-border);
}

.calibration-table thead th {
  color: var(--fp-color-text-muted);
  font-size: 0.75rem;
  white-space: nowrap;
}

.calibration-table td {
  font-variant-numeric: tabular-nums;
}

.calibration-empty {
  color: var(--fp-color-text-muted);
}

@media (max-width: 72rem) {
  .workbench-columns {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 64rem) {
  .pulse-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 42rem) {
  .workbench-head,
  .pulse-grid {
    grid-template-columns: 1fr;
  }

  .workbench-head-actions,
  .workbench-head-actions select,
  .workbench-head-actions .button {
    width: 100%;
  }

  .queue-head {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
