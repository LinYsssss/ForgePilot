<template>
  <div class="report-summary">
    <div class="risk-dial" :class="'r-' + report.overallRisk">{{ report.overallRisk }}</div>
    <div class="rs-body">
      <h3>{{ report.summary || '审查完成' }}</h3>
      <div class="rs-meta">
        <span class="sev-tally risk-NONE">共 {{ report.issues.length }} 问题</span>
        <span v-if="severityTally.HIGH" class="sev-tally risk-HIGH">{{ severityTally.HIGH }} 高危</span>
        <span v-if="severityTally.MEDIUM" class="sev-tally risk-MEDIUM">{{ severityTally.MEDIUM }} 中危</span>
        <span v-if="severityTally.LOW" class="sev-tally risk-LOW">{{ severityTally.LOW }} 低危</span>
      </div>
      <div class="sev-strip" v-if="severityStrip.length" role="img" aria-label="严重度分布">
        <span v-for="seg in severityStrip" :key="seg.key" class="sev-seg" :style="{ width: seg.pct + '%', background: seg.color }" :title="`${seg.label}危 ${seg.count}`"></span>
      </div>
    </div>
  </div>
</template>

<script setup>
// 审查报告摘要(纯展示):风险表盘 + 严重度统计 + 分布条。
// Precision Workbench 重铸:样式收归本组件 scoped,配色全走 tokens。
import { computed } from 'vue'

const props = defineProps({ report: { type: Object, required: true } })

const SEV_META = [
  { key: 'HIGH', label: '高', color: 'var(--rs-risk-high-solid)' },
  { key: 'MEDIUM', label: '中', color: 'var(--rs-risk-medium-solid)' },
  { key: 'LOW', label: '低', color: 'var(--rs-risk-low-solid)' },
]
const severityTally = computed(() => {
  const t = { HIGH: 0, MEDIUM: 0, LOW: 0, NONE: 0 }
  for (const i of (props.report.issues || [])) t[i.severity] = (t[i.severity] || 0) + 1
  return t
})
const severityStrip = computed(() => {
  const t = severityTally.value
  const total = SEV_META.reduce((s, m) => s + (t[m.key] || 0), 0) || 1
  return SEV_META.map(m => ({ ...m, count: t[m.key] || 0, pct: (t[m.key] || 0) / total * 100 })).filter(m => m.count > 0)
})
</script>

<style scoped>
/* Precision Workbench · 报告摘要头。risk-dial 表盘保留 conic-gradient
   手绘实现(HIGH 92% / MEDIUM 60% / LOW 32% / NONE 12% 弧长不变),
   仅换 tokens 色(--rs-risk-*-solid 弧、*-text 文字、内盘对齐容器底);
   sev-strip 分布条同理(segment 色由脚本注入 --rs-risk-*-solid)。
   scoped 同名重定义清单(styles.css 冻结期间由本段供视觉,删除后不受
   影响):.report-summary、.risk-dial(.r-* 四级)、.rs-body(h3)、.rs-meta、
   .sev-tally(.risk-* 四级)、.sev-strip/.sev-seg */
.report-summary {
  display: grid; grid-template-columns: auto 1fr; gap: var(--sp-4); align-items: center;
  padding: var(--sp-4) var(--sp-5); margin-bottom: var(--sp-5);
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-base);
  background: var(--rs-fill-lighter);
  font-family: var(--rs-font-body);
  color: var(--rs-text);
}

/* ---- 风险表盘(riskLevel 全站唯一四级映射;内盘色 = 容器底色) ---- */
.risk-dial {
  width: 76px; height: 76px; border-radius: 50%; display: grid; place-items: center;
  font-family: var(--rs-font-body); font-weight: 800; font-size: var(--rs-fs-sm);
  text-transform: uppercase; letter-spacing: 0.04em;
  position: relative;
}
.risk-dial.r-HIGH { color: var(--rs-risk-high-text); background: radial-gradient(closest-side, var(--rs-fill-lighter) 64%, transparent 65%), conic-gradient(var(--rs-risk-high-solid) 92%, var(--rs-border)); }
.risk-dial.r-MEDIUM { color: var(--rs-risk-medium-text); background: radial-gradient(closest-side, var(--rs-fill-lighter) 64%, transparent 65%), conic-gradient(var(--rs-risk-medium-solid) 60%, var(--rs-border)); }
.risk-dial.r-LOW { color: var(--rs-risk-low-text); background: radial-gradient(closest-side, var(--rs-fill-lighter) 64%, transparent 65%), conic-gradient(var(--rs-risk-low-solid) 32%, var(--rs-border)); }
.risk-dial.r-NONE { color: var(--rs-risk-none-text); background: radial-gradient(closest-side, var(--rs-fill-lighter) 64%, transparent 65%), conic-gradient(var(--rs-risk-none-solid) 12%, var(--rs-border)); }

.rs-body h3 { margin: 0 0 var(--sp-1); font-family: var(--rs-font-body); font-size: var(--rs-fs-md); font-weight: 700; color: var(--rs-text); }
.rs-meta { display: flex; gap: var(--sp-2); flex-wrap: wrap; margin-top: var(--sp-2); }

/* ---- 严重度统计小胶囊(复合选择器 (0,2,0) 稳压全局 .risk-* 单类) ---- */
.sev-tally {
  display: inline-flex; align-items: center; gap: var(--sp-1);
  padding: 3px 9px;
  border: 1px solid transparent;
  border-radius: var(--rs-radius-round);
  font-size: var(--rs-fs-xs); font-weight: 700; line-height: 1.5; white-space: nowrap;
}
.sev-tally.risk-HIGH { color: var(--rs-risk-high-text); background: var(--rs-risk-high-bg); border-color: var(--rs-risk-high-border); }
.sev-tally.risk-MEDIUM { color: var(--rs-risk-medium-text); background: var(--rs-risk-medium-bg); border-color: var(--rs-risk-medium-border); }
.sev-tally.risk-LOW { color: var(--rs-risk-low-text); background: var(--rs-risk-low-bg); border-color: var(--rs-risk-low-border); }
.sev-tally.risk-NONE { color: var(--rs-risk-none-text); background: var(--rs-risk-none-bg); border-color: var(--rs-risk-none-border); }

/* ---- 严重度分布条 ---- */
.sev-strip { display: flex; gap: 2px; height: 7px; margin-top: var(--sp-3); border-radius: var(--rs-radius-round); overflow: hidden; max-width: 340px; }
.sev-seg { height: 100%; min-width: 3px; border-radius: 2px; }

/* ---- 窄屏:折单列居中(与原全局 520 断点逐点一致) ---- */
@media (max-width: 520px) {
  .report-summary { grid-template-columns: 1fr; text-align: center; justify-items: center; }
}
</style>
