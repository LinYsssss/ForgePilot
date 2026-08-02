<template>
  <div v-if="comparePair" class="panel compare-panel">
    <div class="panel-head">
      <div>
        <h2>对比审查 · <span class="mono">{{ shortCommit(comparePair.commitId) }}</span></h2>
        <div class="sub">同一提交：带知识库(全部 INDEXED 文档) vs 不带知识库</div>
      </div>
      <div class="head-actions">
        <button v-if="!ready" class="sm secondary" :disabled="busy.compareLoad" @click="run(loadComparePairReports, 'compareLoad')">
          <span v-if="busy.compareLoad" class="spinner dark"></span>刷新对比数据
        </button>
        <button class="sm secondary" @click="clearCompare">关闭对比</button>
      </div>
    </div>

    <div v-if="!ready" class="empty compact"><p>两侧报告尚未就绪</p><p>任务完成后自动装载,或点击"刷新对比数据"。</p></div>

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
          <div v-if="!result.onlyWith.length" class="empty compact"><p>无独有发现</p></div>
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
          <div v-if="!result.both.length" class="empty compact"><p>无共有发现</p></div>
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
          <div v-if="!result.onlyWithout.length" class="empty compact"><p>无独有发现</p></div>
        </section>
      </div>
    </template>
  </div>
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
