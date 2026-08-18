<template>
  <InkPageFrame active-key="metrics" context-label="当前项目" rail-title="度量口径">
    <MetricsPaper :data="metrics" :loading="metricsLoading" :error="metricsError" :window-value="metricsWindow" :windows="METRICS_WINDOWS" :section="section" :sections="sections" @retry="reload" @window="setWindow" @section="setSection">
      <template #ai-logs><AiLogsPaper :logs="aiLogs" :scope="aiLogScope" :selected="selectedAiLog" :page="aiLogPage" :total-pages="aiLogTotalPages" :loading="aiLogsLoading" :error="aiLogsError" @select="log => selectedAiLog = log" @refresh="loadLogs" @prev="prevAiLogPage" @next="nextAiLogPage" /></template>
    </MetricsPaper>
    <template #rail><p class="rail-note">UI 聚合只读 ai_call_log；Prometheus 仅用于运维交叉验证。</p><p class="rail-note">Requirement 没有状态历史，本页不把 updatedAt 称为交付周期，也不发明综合分。</p></template>
  </InkPageFrame>
</template>
<script setup>
import { ref, watch } from 'vue'
import InkPageFrame from '../features/shell/InkPageFrame.vue'
import MetricsPaper from '../features/metrics/MetricsPaper.vue'
import AiLogsPaper from '../features/metrics/AiLogsPaper.vue'
import { nav } from '../nav.js'
import { useSession } from '../composables/useSession.js'
import { METRICS_WINDOWS, normalizeMetricsWindow, useDevelopmentMetrics } from '../composables/useDevelopmentMetrics.js'
import { useAiLogs } from '../composables/useAiLogs.js'
const sections=[{key:'quality',label:'研发质量'},{key:'requirements',label:'需求质量'},{key:'efficiency',label:'处理效率'},{key:'ai',label:'AI 指标'}]
const { activeProject }=useSession();const { metricsWindow,metrics,metricsLoading,metricsError,loadMetrics,reset }=useDevelopmentMetrics()
const {aiLogs,aiLogScope,selectedAiLog,aiLogPage,aiLogTotalPages,aiLogsLoading,aiLogsError,loadAiLogs,nextAiLogPage,prevAiLogPage,reset:resetAi}=useAiLogs()
const section=ref('quality')
function queryState(){const q=nav.query();return {section:sections.some(x=>x.key===q.section)?q.section:'quality',window:normalizeMetricsWindow(q.window),taskId:q.taskId||null}}
function syncQuery(){const state=queryState();section.value=state.section;metricsWindow.value=state.window;return state}
function pushQuery(patch){nav.push({name:'metrics',query:{...nav.query(),...patch}})}
function setWindow(value){pushQuery({window:normalizeMetricsWindow(value)})}
function setSection(value){pushQuery({section:value})}
function loadLogs(){return loadAiLogs(queryState().taskId)}
async function reload(){await loadMetrics(metricsWindow.value);if(section.value==='ai')await loadLogs()}
watch(() => [activeProject.value?.projectId, nav.query().section, nav.query().window, nav.query().taskId], async () => {reset();resetAi();const state=syncQuery();if(!activeProject.value)return;await loadMetrics(state.window).catch(()=>{});if(state.section==='ai')await loadAiLogs(state.taskId).catch(()=>{})},{immediate:true})
</script>
<style scoped>.rail-note{margin:0 0 var(--ink-sp-12);color:var(--ink-muted);line-height:var(--ink-lh-body)}</style>
