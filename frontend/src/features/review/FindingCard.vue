<script setup lang="ts">
import { computed } from "vue";

import { formatDateTime } from "../../lib/datetime";
import type { ProjectRole } from "../project/api";
import type {
  Finding,
  FindingAction,
  FindingEvent,
  FindingStatus,
  ReviewDecision,
} from "./api";
import {
  availableMoves,
  FINDING_ACTION_LABELS,
  FINDING_CONTINUITY_LABELS,
  FINDING_CONTINUITY_TONES,
  FINDING_STATUS_LABELS,
  FINDING_STATUS_TONES,
  FINDING_TYPE_LABELS,
  REVIEW_DECISION_LABELS,
  REVIEW_DECISION_TONES,
} from "./labels";

const props = defineProps<{
  finding: Finding;
  /** The parent Review's verdict, shown next to the finding and never merged into it. */
  reviewDecision: ReviewDecision;
  assigneeName: string | null;
  /** Null while the project has not loaded; no action is offered until it has. */
  role: ProjectRole | null;
  pending: boolean;
  events: FindingEvent[] | null;
  eventsPending: boolean;
  eventsError: string | null;
}>();

const emit = defineEmits<{
  move: [target: FindingStatus, action: FindingAction];
  showEvents: [];
}>();

const moves = computed(() =>
  availableMoves(props.finding.status, props.finding.continuity, props.role),
);

const locator = computed(() => {
  const path = props.finding.path;
  if (path === null || path === "") {
    return "无文件路径";
  }
  return props.finding.line === null ? `${path} · 无精确行号` : `${path}:${props.finding.line}`;
});
</script>

<template>
  <li class="record finding">
    <div class="record-head">
      <h3 class="record-title">发现 {{ finding.id }}</h3>
      <span class="badge badge-neutral finding-type">
        {{ FINDING_TYPE_LABELS[finding.findingType] }}
      </span>
      <code class="finding-locator">{{ locator }}</code>
    </div>

    <!--
      PRD.md:131 and :135 keep these four apart: the human lifecycle, the
      cross-round lineage, the model's confidence, and the Review's one-shot
      Decision are four different questions. They are four <dt>/<dd> pairs and
      never one combined risk badge.
    -->
    <dl class="meta-list finding-marks">
      <div>
        <dt>人工状态</dt>
        <dd class="finding-status">
          <span :class="['badge', `badge-${FINDING_STATUS_TONES[finding.status]}`]">
            {{ FINDING_STATUS_LABELS[finding.status] }}
          </span>
        </dd>
      </div>
      <div>
        <dt>跨轮血缘</dt>
        <dd class="finding-continuity">
          <span :class="['badge', `badge-${FINDING_CONTINUITY_TONES[finding.continuity]}`]">
            {{ FINDING_CONTINUITY_LABELS[finding.continuity] }}
          </span>
        </dd>
      </div>
      <div>
        <dt>AI 置信度</dt>
        <dd class="finding-ai-confidence">
          <span class="badge badge-neutral">未记录</span>
        </dd>
      </div>
      <div>
        <dt>所属 Review 的 Decision</dt>
        <dd class="review-decision-mark">
          <span :class="['badge', `badge-${REVIEW_DECISION_TONES[reviewDecision]}`]">
            {{ REVIEW_DECISION_LABELS[reviewDecision] }}
          </span>
        </dd>
      </div>
    </dl>
    <p class="field-hint">
      finding 表没有置信度列，因此这里显示「未记录」而不是猜一个数字。四个标记各自独立，不合并为一个综合徽章。
    </p>

    <p class="evidence-label">证据摘录</p>
    <pre class="evidence">{{ finding.evidence ?? "本条 Finding 没有证据摘录。" }}</pre>

    <dl class="meta-list finding-details">
      <div>
        <dt>验收标准</dt>
        <dd>{{ finding.acKey ?? "不针对单条 AC" }}</dd>
      </div>
      <div>
        <dt>需求版本</dt>
        <dd>
          {{
            finding.requirementRevisionId === null
              ? "未关联需求版本"
              : `需求版本 ${finding.requirementRevisionId}`
          }}
        </dd>
      </div>
      <div>
        <dt>认领人</dt>
        <dd class="finding-assignee">{{ assigneeName ?? "未认领" }}</dd>
      </div>
      <div>
        <dt>血缘来源</dt>
        <dd>
          {{
            finding.carriedFromFindingId === null
              ? "本轮首次出现"
              : `继承自发现 ${finding.carriedFromFindingId}`
          }}
        </dd>
      </div>
      <div>
        <dt>finding key</dt>
        <dd><code>{{ finding.findingKey }}</code></dd>
      </div>
      <div>
        <dt>证据 hash</dt>
        <dd><code>{{ finding.evidenceHash ?? "未记录" }}</code></dd>
      </div>
      <div>
        <dt>依据 hash</dt>
        <dd><code>{{ finding.basisHash ?? "未记录" }}</code></dd>
      </div>
    </dl>

    <div class="form-actions">
      <button
        v-for="move in moves"
        :key="move.action"
        type="button"
        class="button"
        :data-action="move.action"
        :disabled="pending"
        @click="emit('move', move.target, move.action)"
      >
        {{ FINDING_ACTION_LABELS[move.action] }}
      </button>
      <button
        type="button"
        class="button finding-events-button"
        :disabled="eventsPending"
        @click="emit('showEvents')"
      >
        查看审计记录
      </button>
    </div>
    <p v-if="moves.length === 0" class="field-hint">
      以你当前的角色，这条 Finding 没有可执行的流转。
    </p>

    <p v-if="eventsError" class="alert" role="alert">{{ eventsError }}</p>
    <ol v-if="events !== null" class="finding-events">
      <li v-for="event in events" :key="event.id">
        {{ formatDateTime(event.createdAt) }} ·
        {{ FINDING_ACTION_LABELS[event.action] }} ·
        {{ FINDING_STATUS_LABELS[event.fromStatus] }} →
        {{ FINDING_STATUS_LABELS[event.toStatus] }} ·
        操作人 {{ event.actorId }}
        <span v-if="event.comment">· {{ event.comment }}</span>
      </li>
      <li v-if="events.length === 0">这条 Finding 还没有流转记录。</li>
    </ol>
  </li>
</template>

<style scoped>
.finding {
  border-color: var(--fp-color-border-strong);
  background: var(--fp-gradient-panel);
}

.finding .record-head {
  padding-bottom: var(--fp-space-4);
  border-bottom: 0.0625rem solid var(--fp-color-border);
}

.finding .record-title {
  margin-right: auto;
}

.finding-locator {
  max-width: 100%;
  padding: var(--fp-space-2) var(--fp-space-3);
  border: 0.0625rem solid var(--fp-color-border);
  border-radius: var(--fp-radius-sm);
  background: var(--fp-color-canvas-muted);
  color: var(--fp-color-accent-inverse);
  font-family: var(--fp-font-mono);
  font-size: 0.8125rem;
  word-break: break-all;
}

.finding-marks {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-top: var(--fp-space-4);
}

.finding-marks > div {
  padding: var(--fp-space-3);
  border: 0.0625rem solid var(--fp-color-border);
  border-radius: var(--fp-radius-sm);
  background: var(--fp-color-canvas-muted);
}

.evidence-label {
  margin: var(--fp-space-4) 0 var(--fp-space-1);
  color: var(--fp-color-text-muted);
  font-size: 0.8125rem;
  font-weight: 650;
}

.evidence {
  max-height: 18rem;
  margin: 0 0 var(--fp-space-4);
  padding: var(--fp-space-3);
  overflow: auto;
  border: 0.0625rem solid var(--fp-color-border);
  border-radius: var(--fp-radius-sm);
  border-left: 0.1875rem solid var(--fp-color-accent);
  background: var(--fp-color-canvas-muted);
  font-family: var(--fp-font-mono);
  font-size: 0.8125rem;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.finding-details {
  padding: var(--fp-space-4);
  border: 0.0625rem solid var(--fp-color-border);
  border-radius: var(--fp-radius-md);
  background: var(--fp-color-surface-glass);
}

.finding .form-actions {
  margin-top: var(--fp-space-5);
  padding-top: var(--fp-space-5);
  border-top: 0.0625rem solid var(--fp-color-border);
}

.finding-events {
  display: grid;
  gap: var(--fp-space-2);
  margin: var(--fp-space-4) 0 0;
  padding-left: var(--fp-space-6);
  color: var(--fp-color-text-muted);
  font-size: 0.8125rem;
}

@media (max-width: 64rem) {
  .finding-marks {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 42rem) {
  .finding-marks {
    grid-template-columns: 1fr;
  }
}
</style>
