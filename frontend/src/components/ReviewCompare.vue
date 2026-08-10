<template>
  <el-card v-if="comparePair" shadow="never" class="compare-panel">
    <template #header>
      <div class="card-head">
        <div>
          <h2>对比审查 · <span class="mono">{{ shortCommit(comparePair.commitId) }}</span></h2>
          <div class="card-sub">同一提交：带知识库(全部 INDEXED 文档) vs 不带知识库</div>
        </div>
        <div class="head-actions">
          <el-button v-if="!ready" size="small" :loading="busy.compareLoad" :disabled="busy.compareLoad" @click="run(loadComparePairReports, 'compareLoad')">刷新对比数据</el-button>
          <el-button size="small" @click="clearCompare">关闭对比</el-button>
        </div>
      </div>
    </template>

    <el-empty v-if="!ready" description="两侧报告尚未就绪" :image-size="64">
      <p class="empty-extra">任务完成后自动装载,或点击"刷新对比数据"。</p>
    </el-empty>

    <template v-else>
      <div class="compare-summary">
        <div class="cs-tile"><span>带知识库发现</span><strong>{{ result.summary.withTotal }}</strong></div>
        <div class="cs-tile"><span>不带知识库发现</span><strong>{{ result.summary.withoutTotal }}</strong></div>
        <div class="cs-tile accent"><span>知识库多发现</span><strong>+{{ result.summary.extraFromKnowledge }}</strong></div>
        <div class="cs-tile accent"><span>其中引用文档/事故</span><strong>{{ result.summary.knowledgeSignals }}</strong></div>
      </div>

      <div class="compare-grid">
        <section class="compare-col">
          <h3 class="cc-head cc-with">仅「带知识库」发现（{{ result.onlyWith.length }}）</h3>
          <article v-for="issue in result.onlyWith" :key="issue.issueId" class="issue compare-issue" :class="['sevbar-' + issue.severity, { 'kb-signal': signal(issue) }]">
            <div class="issue-head">
              <span class="badge" :class="'sev-' + issue.severity">{{ issue.severity }}</span>
              <span v-if="signal(issue)" class="badge plain kb-badge" title="引用了知识库文档或历史事故">📚 知识库信号</span>
              <h4>{{ issue.title }}</h4>
            </div>
            <p>{{ issue.description }}</p>
            <div class="callout co-evidence" v-if="issue.evidence"><span class="co-tag">证据</span><span class="co-body">{{ issue.evidence }}</span></div>
          </article>
          <el-empty v-if="!result.onlyWith.length" description="无独有发现" :image-size="48" />
        </section>

        <section class="compare-col">
          <h3 class="cc-head">双方共有（{{ result.both.length }}）</h3>
          <article v-for="pair in result.both" :key="pair.withDocs.issueId" class="issue compare-issue" :class="'sevbar-' + pair.withDocs.severity">
            <div class="issue-head">
              <span class="badge" :class="'sev-' + pair.withDocs.severity">{{ pair.withDocs.severity }}</span>
              <h4>{{ pair.withDocs.title }}</h4>
            </div>
            <p>{{ pair.withDocs.description }}</p>
          </article>
          <el-empty v-if="!result.both.length" description="无共有发现" :image-size="48" />
        </section>

        <section class="compare-col">
          <h3 class="cc-head cc-without">仅「不带知识库」发现（{{ result.onlyWithout.length }}）</h3>
          <article v-for="issue in result.onlyWithout" :key="issue.issueId" class="issue compare-issue" :class="'sevbar-' + issue.severity">
            <div class="issue-head">
              <span class="badge" :class="'sev-' + issue.severity">{{ issue.severity }}</span>
              <h4>{{ issue.title }}</h4>
            </div>
            <p>{{ issue.description }}</p>
          </article>
          <el-empty v-if="!result.onlyWithout.length" description="无独有发现" :image-size="48" />
        </section>
      </div>
    </template>
  </el-card>
</template>

<script setup>
// 对比视图:纯展示。数据来自 useReviews.comparePair,对齐算法在 utils/compareReports。
import { computed } from 'vue'
import { shortCommit } from '../utils/format.js'
import { compareReports, hasKnowledgeSignal } from '../utils/compareReports.js'
import { useBusy } from '../composables/useBusy.js'
import { useKnowledge } from '../composables/useKnowledge.js'
import { useReviews } from '../composables/useReviews.js'

const { busy, run } = useBusy()
const { documents } = useKnowledge()
const { comparePair, loadComparePairReports, clearCompare } = useReviews()

const docNames = computed(() => documents.value.map(d => d.fileName))
const ready = computed(() => !!(comparePair.value?.withReport && comparePair.value?.withoutReport))
const result = computed(() => ready.value
  ? compareReports(comparePair.value.withReport, comparePair.value.withoutReport, docNames.value)
  : null)

function signal(issue) { return hasKnowledgeSignal(issue, docNames.value) }
</script>

<style scoped>
/* Precision Workbench · 对比审查(带/不带知识库)。el-card 外壳 + tokens 直供;
   三栏保留 CSS grid(1100 折单栏断点语义保留),对比语义色全走 tokens。
   scoped 同名重定义清单(styles.css 冻结期间由本段供视觉,删除后不受影响):
   .compare-summary/.cs-tile(.accent)、.compare-grid、.cc-head(.cc-with/
   .cc-without)、.issue/.compare-issue(.sevbar-* 五级/.kb-signal)、
   .issue-head(h4)、.callout(.co-tag/.co-body/.co-evidence)、
   .badge(.plain、.sev-*、.kb-badge)、.mono */
.card-head { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); flex-wrap: wrap; }
.card-head h2 { margin: 0; font-family: var(--rs-font-body); font-size: var(--rs-fs-lg); font-weight: 700; color: var(--rs-text); }
.card-sub { margin-top: 3px; color: var(--rs-text-dim); font-size: var(--rs-fs-sm); font-weight: 400; }
.head-actions { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.head-actions .el-button + .el-button { margin-left: 0; }

.mono { font-family: var(--rs-font-mono); font-feature-settings: "liga" 0; }

.empty-extra { margin: 0; color: var(--rs-text-dim); font-size: var(--rs-fs-sm); }

/* ---- 四磁贴(概览页磁贴手法:描边平贴,accent 双贴 teal 强调) ---- */
.compare-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--sp-3); margin-bottom: var(--sp-5); }
.cs-tile {
  display: flex; flex-direction: column; gap: 4px;
  padding: var(--sp-4);
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-base);
  background: var(--rs-fill-lighter);
}
.cs-tile span { font-size: var(--rs-fs-xs); color: var(--rs-text-dim); font-weight: 700; }
.cs-tile strong { font-family: var(--rs-font-body); font-size: var(--rs-fs-2xl); font-weight: 800; letter-spacing: -0.02em; color: var(--rs-text); }
.cs-tile.accent { border-color: var(--el-color-primary-light-7); background: var(--el-color-primary-light-9); }
.cs-tile.accent strong { color: var(--rs-primary); }

/* ---- 三栏(保留 grid;1100 以下折单栏、磁贴降两列,与原断点一致) ---- */
.compare-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--sp-4); align-items: start; }

/* ---- 列头(cc-with teal 强调 / cc-without 中性灰) ---- */
.cc-head {
  margin: 0 0 var(--sp-3);
  padding: var(--sp-2) var(--sp-3);
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-sm);
  background: var(--rs-fill-lighter);
  font-family: var(--rs-font-body); font-size: var(--rs-fs-sm); font-weight: 800; letter-spacing: 0.02em;
  color: var(--rs-text-soft);
}
.cc-head.cc-with { color: var(--rs-primary); border-color: var(--el-color-primary-light-7); background: var(--el-color-primary-light-9); }
.cc-head.cc-without { color: var(--rs-risk-none-text); }

/* ---- 对比 issue 卡(左侧严重度色条;kb-signal 置于 sevbar 之后,
   四边描边整体转 teal,与迁移前层叠次序等价) ---- */
.issue {
  border: 1px solid var(--rs-border);
  border-left: 3px solid var(--rs-border);
  border-radius: var(--rs-radius-sm);
  padding: var(--sp-5);
  margin-bottom: var(--sp-3);
  background: var(--rs-surface);
  transition: border-color var(--rs-t-fast), box-shadow var(--rs-t-fast);
}
.issue:hover { box-shadow: var(--rs-shadow-sm); }
.issue.sevbar-CRITICAL { border-left-color: var(--rs-sev-critical-solid); }
.issue.sevbar-HIGH { border-left-color: var(--rs-risk-high-solid); }
.issue.sevbar-MEDIUM { border-left-color: var(--rs-risk-medium-solid); }
.issue.sevbar-LOW { border-left-color: var(--rs-risk-low-solid); }
.issue.sevbar-INFO { border-left-color: var(--rs-sev-info-solid); }
.compare-issue { padding: var(--sp-4); }
.compare-issue.kb-signal { border-color: var(--el-color-primary-light-3); box-shadow: 0 0 0 2px var(--el-color-primary-light-8); }

.issue-head { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; margin-bottom: var(--sp-2); }
.issue-head h4 { margin: 0 0 0 2px; font-family: var(--rs-font-body); font-size: var(--rs-fs-md); font-weight: 700; color: var(--rs-text); }
.compare-issue h4 { font-size: var(--rs-fs-base); }
.issue p { margin: var(--sp-2) 0; color: var(--rs-text-soft); font-size: var(--rs-fs-base); line-height: 1.6; }

/* ---- 证据 callout(中性码面;结构不动) ---- */
.issue .callout {
  position: relative; display: grid; grid-template-columns: auto 1fr; gap: var(--sp-3); align-items: baseline;
  margin: var(--sp-2) 0; padding: var(--sp-3) var(--sp-3) var(--sp-3) var(--sp-4);
  border-radius: var(--rs-radius-sm); border: 1px solid var(--rs-border-faint);
  transition: border-color var(--rs-t-fast) var(--rs-ease-out), background var(--rs-t-fast);
}
.issue .callout::before { content: ""; position: absolute; left: 0; top: 8px; bottom: 8px; width: 3px; border-radius: var(--rs-radius-round); }
.issue .callout .co-tag { font-size: var(--rs-fs-xs); font-weight: 800; letter-spacing: 0.04em; padding: 2px 8px; border-radius: var(--rs-radius-round); white-space: nowrap; }
.issue .callout .co-body { color: var(--rs-text-soft); font-size: var(--rs-fs-sm); line-height: 1.62; }
.co-evidence { background: var(--rs-fill-lighter); }
.co-evidence::before { background: var(--rs-text-dim); }
.co-evidence .co-tag { color: var(--rs-text-dim); background: var(--rs-fill); }
.co-evidence .co-body { font-family: var(--rs-font-mono); font-size: var(--rs-fs-xs); color: var(--rs-text-soft); word-break: break-word; }
.co-evidence:hover { border-color: var(--rs-border-strong); }

/* ---- 徽标(sev-* finding 五级;kb-badge teal 徽标) ---- */
.badge {
  display: inline-flex; align-items: center; gap: var(--sp-1);
  padding: 2px 9px;
  border: 1px solid transparent;
  border-radius: var(--rs-radius-round);
  font-size: var(--rs-fs-xs); font-weight: 700; letter-spacing: 0.02em;
  line-height: 1.5; white-space: nowrap;
}
.badge::before { content: ""; width: 6px; height: 6px; border-radius: 50%; background: currentColor; box-shadow: none; }
.badge.plain::before { content: none; display: none; }
.badge.plain { background: var(--rs-fill); border-color: transparent; color: var(--rs-text-soft); font-family: var(--rs-font-mono); font-weight: 600; }
.sev-HIGH { color: var(--rs-risk-high-text); background: var(--rs-risk-high-bg); border-color: var(--rs-risk-high-border); }
.sev-MEDIUM { color: var(--rs-risk-medium-text); background: var(--rs-risk-medium-bg); border-color: var(--rs-risk-medium-border); }
.sev-LOW { color: var(--rs-risk-low-text); background: var(--rs-risk-low-bg); border-color: var(--rs-risk-low-border); }
.sev-NONE { color: var(--rs-risk-none-text); background: var(--rs-risk-none-bg); border-color: var(--rs-risk-none-border); }
.sev-CRITICAL { color: var(--rs-sev-critical-text); background: var(--rs-sev-critical-bg); border-color: var(--rs-sev-critical-border); }
.sev-INFO { color: var(--rs-sev-info-text); background: var(--rs-sev-info-bg); border-color: var(--rs-sev-info-border); }
/* badge.plain 之后声明,同特异性(0,2,0)后者胜:知识库信号徽标着 teal */
.badge.plain.kb-badge { color: var(--rs-primary); background: var(--el-color-primary-light-9); }

@media (max-width: 1100px) {
  .compare-grid { grid-template-columns: 1fr; }
  .compare-summary { grid-template-columns: repeat(2, 1fr); }
}
</style>
