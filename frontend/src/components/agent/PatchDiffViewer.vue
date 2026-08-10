<template>
  <el-card class="patch-viewer" shadow="never">
    <template #header>
      <div class="rs-card-head"><h3>Patch Diff</h3><a v-if="downloadUrl" :href="downloadUrl" class="download-link" download>下载 Patch</a></div>
    </template>
    <div v-if="lines.length" class="diff numbered-diff" role="region" aria-label="Patch unified diff" tabindex="0">
      <div v-for="(line, i) in lines" :key="i" class="diff-row" :class="line.cls"
        :data-diff-file="line.file || null" :data-diff-line="line.newNo || null">
        <span class="diff-number" aria-hidden="true">{{ line.newNo }}</span><code>{{ line.text || ' ' }}</code>
      </div>
    </div>
    <el-empty v-else description="暂无 Patch" :image-size="60" />
    <details class="validation-log"><summary>验证日志</summary><pre>{{ validationLog || '暂无验证日志' }}</pre></details>
  </el-card>
</template>
<script setup>
import { computed } from 'vue'
import { diffLines } from '../../utils/labels.js'
const props = defineProps({ patchContent: { type: String, default: '' }, validationLog: { type: String, default: '' }, downloadUrl: { type: String, default: '' } })
// 复用 labels.diffLines 的 hunk 行号推导,并跟踪 "+++ b/<path>" 得到行所属文件,
// 供 citation 定位(useAgentWorkspace.focusEvidenceAnchor 按 file+line 查找)。
const lines = computed(() => {
  let file = ''
  return diffLines(props.patchContent || '').map(line => {
    if (line.text.startsWith('+++ b/')) file = line.text.slice(6).trim()
    else if (line.text.startsWith('+++ ')) file = line.text.slice(4).trim()
    return { ...line, file: line.cls === 'meta' || line.cls === 'hunk' ? '' : file }
  })
})
</script>
<style scoped>
/* Precision Workbench Patch 查看器:el-card 外壳,diff 渲染逻辑与
   data-diff-file/data-diff-line 定位属性零改动;亮码面走 --rs-diff-*。
   overflow:auto + max-height:520px 滚动容器原样保留(本页无 sticky,
   el-card body 的 overflow:auto 不构成嵌套滚动:卡体无高度约束不产生滚动)。
   scoped 同名重定义清单:.numbered-diff/.diff-row(.add/.del/.hunk/.meta)/
   .diff-number/.evidence-focus */
.rs-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.rs-card-head h3 {
  margin: 0;
  font-family: var(--rs-font-body);
  font-size: var(--rs-fs-md);
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--rs-text);
}

/* 下载链接(data: URL + download 属性原样) */
.download-link {
  color: var(--rs-primary);
  font-size: var(--rs-fs-sm);
  font-weight: 600;
  text-decoration: none;
}
.download-link:hover { color: var(--rs-primary-dark); text-decoration: underline; }
.download-link:focus-visible { outline: 2px solid var(--rs-primary); outline-offset: 2px; border-radius: 2px; }

/* ---- 带行号 unified diff(亮色码面) ---- */
.numbered-diff {
  overflow: auto;
  max-height: 520px;
  font-family: var(--rs-font-mono);
  font-feature-settings: "liga" 0;
  font-size: var(--rs-fs-sm);
  background: var(--rs-diff-surface);
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-sm);
  scrollbar-color: var(--rs-border-strong) transparent;
}
.numbered-diff:focus-visible { outline: 2px solid var(--rs-primary); outline-offset: 2px; }
.diff-row {
  display: grid;
  grid-template-columns: 42px minmax(max-content, 1fr);
  white-space: pre;
  line-height: 1.55;
  color: var(--rs-text-soft);
}
.diff-number {
  color: var(--rs-diff-lineno);
  text-align: right;
  padding-right: 12px;
  border-right: 1px solid var(--rs-border-faint);
  user-select: none;
  font-size: var(--rs-fs-xs);
  line-height: inherit;
}
.diff-row code { padding-left: 10px; font-family: inherit; background: none; }
.diff-row.add { background: var(--rs-diff-add-bg); color: var(--rs-diff-add-text); }
.diff-row.del { background: var(--rs-diff-del-bg); color: var(--rs-diff-del-text); }
.diff-row.hunk { background: var(--rs-diff-hunk-bg); color: var(--rs-diff-hunk-text); }
.diff-row.meta { color: var(--rs-diff-meta); }

/* 证据锚点高亮(focusEvidenceAnchor 按 file+line 命中 diff 行时挂类) */
.evidence-focus { outline: 2px solid var(--rs-primary); outline-offset: 4px; border-radius: 4px; }

/* ---- 验证日志(原生 details;内容绑定原样) ---- */
.validation-log { margin-top: var(--sp-4); }
.validation-log summary {
  cursor: pointer;
  color: var(--rs-primary);
  font-size: var(--rs-fs-sm);
  font-weight: 600;
}
.validation-log summary:hover { color: var(--rs-primary-dark); text-decoration: underline; }
.validation-log summary:focus-visible { outline: 2px solid var(--rs-primary); outline-offset: 2px; border-radius: var(--rs-radius-sm); }
.validation-log pre {
  margin: var(--sp-2) 0 0;
  padding: var(--sp-3);
  background: var(--rs-diff-surface);
  border: 1px solid var(--rs-border-faint);
  border-radius: var(--rs-radius-sm);
  font-family: var(--rs-font-mono);
  font-size: var(--rs-fs-sm);
  line-height: 1.6;
  color: var(--rs-text-soft);
  white-space: pre-wrap;
  word-break: break-word;
  overflow: auto;
}
</style>
