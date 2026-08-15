<template>
  <InkPageFrame active-key="repository" context-label="当前项目" rail-title="仓库备注">
    <RepositoryPaper
      :form="repoForm"
      :commits="commits"
      :selected-commit="selectedCommit"
      :diff-files="diffFiles"
      :repo-bound="repoBound"
      :needs-token="needsToken"
      :binding="!!busy.bind"
      :loading-commits="!!busy.commits"
      :loading-diff="!!busy.diff"
      @bind="run(bindRepository)"
      @demo="useDemoRepository"
      @load-commits="run(loadCommits)"
      @unbind="run(unbindRepository)"
      @select-commit="selectCommit"
      @load-diff="run(loadDiff)"
      @review-commit="reviewSelectedCommit"
    />

    <template #rail>
      <p class="rail-note">
        令牌只加密存回后端、不回显。<strong>同一个 Git 地址</strong>再次绑定时留空即沿用已存的那一份;
        改绑到别的地址必须重填——后端不会把旧库令牌带到新库。
      </p>
      <p class="rail-note">
        「发起审查」会把选中提交及其父提交填进审查表单,再跳到审查记录页——
        父提交就是 diff 的比较基线。
      </p>
    </template>
  </InkPageFrame>
</template>

<script setup>
import InkPageFrame from '../features/shell/InkPageFrame.vue'
import RepositoryPaper from '../features/repository/RepositoryPaper.vue'
import { useBusy } from '../composables/useBusy.js'
import { useRepository } from '../composables/useRepository.js'
import { useWorkspace } from '../composables/useWorkspace.js'

// 墨境仓库页(实施步骤 8 第三页)。与旧 RepositoryView 取同一批 composable 引用,
// 绑定/解绑/加载提交/看 diff/发起审查的动作语义原样保留,只换表现层。
//
// selectCommit 与 loadDiff 仍走 useWorkspace 而非 useRepository:这两个是跨域动作
// (选中提交要预填审查表单;diff 的比较基线取自审查表单的 baseCommitId),
// 绕过 useWorkspace 直接调 useRepository 会丢掉这层编排。
const { busy, run } = useBusy()
const {
  repoForm, commits, selectedCommit, diffFiles, repoBound, needsToken,
  useDemoRepository, bindRepository, unbindRepository, loadCommits,
} = useRepository()
const { selectCommit, loadDiff, reviewSelectedCommit } = useWorkspace()
</script>

<style scoped>
.rail-note { margin: 0 0 var(--ink-sp-12); color: var(--ink-muted); line-height: var(--ink-lh-body); }
.rail-note:last-child { margin-bottom: 0; }
</style>
