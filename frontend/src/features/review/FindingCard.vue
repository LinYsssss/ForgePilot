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
  FINDING_CATEGORY_LABELS,
  FINDING_CONFIDENCE_LABELS,
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
  /** 父级 Review 的裁定，展示在 finding 旁边，绝不与之合并。 */
  reviewDecision: ReviewDecision;
  assigneeName: string | null;
  /** 项目尚未加载完成时为 null；在此之前不提供任何操作。 */
  roles: ProjectRole[];
  pending: boolean;
  events: FindingEvent[] | null;
  eventsPending: boolean;
  eventsError: string | null;
  selected: boolean;
  actorNames: Record<string, string>;
  comment: string;
}>();

const emit = defineEmits<{
  move: [target: FindingStatus, action: FindingAction, comment: string];
  showEvents: [];
  select: [];
  updateComment: [comment: string];
}>();

const moves = computed(() =>
  availableMoves(props.finding.status, props.finding.continuity, props.roles),
);

const locator = computed(() => {
  const path = props.finding.path;
  if (path === null || path === "") {
    return "无文件路径";
  }
  return props.finding.line === null ? `${path} · 无精确行号` : `${path}:${props.finding.line}`;
});

function actorName(actorId: number): string {
  return props.actorNames[String(actorId)] ?? `用户 ${actorId}`;
}

function updateComment(event: Event): void {
  const target = event.target;
  if (target instanceof HTMLTextAreaElement) {
    emit("updateComment", target.value);
  }
}
</script>

<template>
  <li :class="['record', 'finding', { 'finding-selected': selected }]">
    <div class="record-head">
      <h3 class="record-title">发现 {{ finding.id }}</h3>
      <span class="badge badge-neutral finding-type">
        {{ FINDING_TYPE_LABELS[finding.findingType] }}
      </span>
      <span v-if="finding.category !== null" class="badge badge-neutral finding-category">
        {{ FINDING_CATEGORY_LABELS[finding.category] }}
      </span>
      <code class="finding-locator">{{ locator }}</code>
      <button type="button" class="button button-quiet" @click="emit('select')">
        在证据中定位
      </button>
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
          <span class="badge badge-neutral">
            {{
              finding.confidence === null
                ? "未记录"
                : FINDING_CONFIDENCE_LABELS[finding.confidence]
            }}
          </span>
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
      置信度是模型自报的分档，未经校准，不参与任何自动判定或状态流转；此前产生的 Finding
      没有记录它，显示「未记录」。四个标记各自独立，不合并为一个综合徽章。
    </p>

    <!--
      阅读顺序是「说明 → 证据 → 建议」：先知道问题是什么，再看可核验的原文，
      最后才是怎么办。说明与建议是模型自己的话，证据是逐字引用的源码，标签必须
      让这个区别一眼可见——否则一条未经验证的建议会被读成已核实的结论。
    -->
    <section class="finding-narrative">
      <p class="narrative-label">
        问题说明 <span class="narrative-origin">模型判断</span>
      </p>
      <p class="narrative-body">{{ finding.explanation ?? "模型没有给出问题说明。" }}</p>
    </section>

    <p class="evidence-label">
      证据摘录 <span class="narrative-origin narrative-origin-verifiable">逐字引用</span>
    </p>
    <pre class="evidence">{{ finding.evidence ?? "本条 Finding 没有证据摘录。" }}</pre>

    <section class="finding-narrative">
      <p class="narrative-label">
        修复建议 <span class="narrative-origin">模型建议，未经验证</span>
      </p>
      <p class="narrative-body">{{ finding.suggestion ?? "模型没有给出修复建议。" }}</p>
    </section>

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
    </dl>

    <!--
      三个哈希与 finding key 是可核验性凭据，不是阅读内容：它们决定去重、抑制
      与血缘，但读它们不能帮任何人理解这条问题。收起而不是移除——答辩时仍要
      能当场展开证明抑制机制是真的按哈希工作的。
    -->
    <details class="finding-verifiability">
      <summary>可核验性标识</summary>
      <dl class="meta-list">
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
    </details>

    <div v-if="moves.length > 0" class="field finding-comment-field">
      <label :for="`finding-comment-${finding.id}`">本次流转备注（可选）</label>
      <textarea
        :id="`finding-comment-${finding.id}`"
        :value="comment"
        rows="2"
        maxlength="2000"
        placeholder="记录判断依据、修复说明或打回原因"
        @input="updateComment"
      ></textarea>
    </div>

    <div class="form-actions">
      <button
        v-for="move in moves"
        :key="move.action"
        type="button"
        class="button"
        :data-action="move.action"
        :disabled="pending"
        @click="emit('move', move.target, move.action, comment)"
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
        操作人 {{ actorName(event.actorId) }}
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

.finding-selected {
  border-color: var(--fp-color-warning);
  box-shadow: var(--fp-shadow-elevated), 0 0 2rem var(--fp-color-warning-soft);
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

.evidence-label,
.narrative-label {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fp-space-2);
  align-items: baseline;
  margin: var(--fp-space-4) 0 var(--fp-space-1);
  color: var(--fp-color-text-muted);
  font-size: 0.8125rem;
  font-weight: 650;
}

/*
  「这段话是谁说的」必须与标题同框可见。模型的判断与逐字引用的原文若看起来
  一样，一条未经验证的建议就会被当成已核实的结论。
*/
.narrative-origin {
  padding: 0 var(--fp-space-2);
  border: 0.0625rem solid var(--fp-color-border);
  border-radius: var(--fp-radius-sm);
  color: var(--fp-color-text-muted);
  font-size: 0.75rem;
  font-weight: 500;
}

.narrative-origin-verifiable {
  border-color: var(--fp-color-accent);
  color: var(--fp-color-accent-inverse);
}

/*
  与 .evidence 共用同一组 containment：长说明或长建议在自己的框里滚动，
  页面本身不获得横向溢出（design-contract.md 的组件与漂移规则）。
*/
.narrative-body {
  max-height: 18rem;
  margin: 0;
  padding: var(--fp-space-3);
  overflow: auto;
  border: 0.0625rem solid var(--fp-color-border);
  border-radius: var(--fp-radius-sm);
  background: var(--fp-color-canvas-muted);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.finding-verifiability {
  margin-top: var(--fp-space-4);
}

.finding-verifiability > summary {
  color: var(--fp-color-text-muted);
  cursor: pointer;
  font-size: 0.8125rem;
}

.finding-verifiability .meta-list {
  margin-top: var(--fp-space-3);
}

.finding-verifiability code {
  font-family: var(--fp-font-mono);
  font-size: 0.75rem;
  word-break: break-all;
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

.finding-comment-field {
  margin-top: var(--fp-space-5);
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
