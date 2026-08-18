<template>
  <InkPageFrame active-key="repository" context-label="当前项目" rail-title="仓库备注">
    <nav class="section-tabs" aria-label="仓库分区"><button :class="{ active: section === 'commits' }" @click="setSection('commits')">提交与 Diff</button><button :class="{ active: section === 'pull-requests' }" @click="setSection('pull-requests')">Pull Request</button></nav>
    <RepositoryPaper v-if="section === 'commits'" :form="repoForm" :commits="commits" :selected-commit="selectedCommit" :diff-files="diffFiles" :repo-bound="repoBound" :needs-token="needsToken" :binding="!!busy.bind" :loading-commits="!!busy.commits" :loading-diff="!!busy.diff" @bind="run(bindRepository)" @demo="useDemoRepository" @load-commits="run(loadCommits)" @unbind="run(unbindRepository)" @select-commit="selectCommit" @load-diff="run(loadDiff)" @review-commit="reviewSelectedCommit" />
    <PullRequestsPaper v-else />
    <template #rail><p class="rail-note" v-if="section === 'commits'">令牌只加密存回后端、不回显。同一个 Git 地址再次绑定时留空沿用；改绑新地址必须重填。</p><p class="rail-note" v-else>PR 没有逐条 reviewer assignment；工作台待审队列按项目角色与 reviewState 汇总。</p></template>
  </InkPageFrame>
</template>
<script setup>
import { ref, watch } from 'vue'
import InkPageFrame from '../features/shell/InkPageFrame.vue';import RepositoryPaper from '../features/repository/RepositoryPaper.vue';import PullRequestsPaper from '../features/repository/PullRequestsPaper.vue';import { nav } from '../nav.js';import { useBusy } from '../composables/useBusy.js';import { useRepository } from '../composables/useRepository.js';import { usePullRequests } from '../composables/usePullRequests.js';import { useSession } from '../composables/useSession.js';import { useWorkspace } from '../composables/useWorkspace.js'
const {busy,run}=useBusy();const {activeProject}=useSession();const {repoForm,commits,selectedCommit,diffFiles,repoBound,needsToken,useDemoRepository,bindRepository,unbindRepository,loadCommits}=useRepository();const {pullRequests,activePullRequest,loadPullRequests,selectPullRequest}=usePullRequests();const {selectCommit,loadDiff,reviewSelectedCommit}=useWorkspace();const section=ref('commits')
function normalizedSection(){return nav.query().section==='pull-requests'?'pull-requests':'commits'}
function setSection(value){nav.push({name:'repository',query:{...nav.query(),section:value}})}
watch(()=>[activeProject.value?.projectId,nav.query().section,nav.query().pullRequestId],async()=>{section.value=normalizedSection();if(!activeProject.value||section.value!=='pull-requests')return;await loadPullRequests().catch(()=>{});const id=Number(nav.query().pullRequestId);if(id){const match=pullRequests.value.find(item=>Number(item.pullRequestId)===id);if(match&&activePullRequest.value?.pullRequestId!==match.pullRequestId)await selectPullRequest(match)}},{immediate:true})
</script>
<style scoped>.section-tabs{display:flex;gap:var(--ink-sp-4);margin-bottom:var(--ink-sp-16)}.section-tabs button{min-height:44px;padding:var(--ink-sp-8) var(--ink-sp-16);border:1px solid var(--line-soft);background:var(--surface-paper);color:var(--ink-default)}.section-tabs button.active{border-color:var(--cinnabar);color:var(--cinnabar);font-weight:700}.rail-note{margin:0 0 var(--ink-sp-12);color:var(--ink-muted);line-height:var(--ink-lh-body)}</style>
