<template>
  <div class="view">
      <div class="panel">
        <div class="panel-head">
          <div><h2>仓库配置</h2><div class="sub">绑定 Git 仓库以拉取提交和 diff</div></div>
          <span v-if="repoBound" class="status-pill st-ACTIVE">已绑定</span>
        </div>
        <div class="grid three">
          <label class="field">Git 地址<input v-model="repoForm.repoUrl" placeholder="https://… 或本地演示路径" /></label>
          <label class="field">Provider
            <select v-model="repoForm.provider">
              <option value="GITHUB">GitHub</option>
              <option value="GITLAB">GitLab</option>
              <option value="GITEE">Gitee</option>
              <option value="LOCAL">本地路径</option>
              <option value="OTHER">其他</option>
            </select>
          </label>
          <label class="field">默认分支<input v-model="repoForm.defaultBranch" placeholder="main" /></label>
        </div>
        <label class="field" v-if="needsToken" style="margin-top:16px">
          访问令牌 (私有仓库需要，加密存储，不回显)
          <input v-model="repoForm.accessToken" type="password" :placeholder="repoForm._tokenConfigured ? '已保存令牌，留空则沿用原令牌' : 'ghp_… / glpat-…'" />
        </label>
        <div class="actions">
          <button @click="run(bindRepository)" :disabled="busy.bind">
            <span v-if="busy.bind" class="spinner"></span>{{ repoBound ? '更新绑定' : '绑定仓库' }}
          </button>
          <button class="secondary" @click="useDemoRepository">填入演示仓库</button>
          <button class="secondary" @click="run(loadCommits)" :disabled="!repoBound || busy.commits">
            <span v-if="busy.commits" class="spinner dark"></span>加载 Commit
          </button>
          <button v-if="repoBound" class="danger" @click="run(unbindRepository)">解绑</button>
        </div>
        <p class="hint" v-if="needsToken">⚠ 远程私有仓库请填写访问令牌；公开仓库或本地路径可留空。</p>
      </div>

      <div class="panel">
        <div class="panel-head">
          <div><h2>提交历史</h2><div class="sub">点击某次提交查看变更明细</div></div>
          <span v-if="selectedCommit" class="badge plain">已选 {{ shortCommit(selectedCommit.commitId) }}</span>
        </div>
        <div v-if="busy.commits" class="skeleton"><div class="sk-row" v-for="n in 4" :key="n"></div></div>
        <div v-else-if="!commits.length" class="empty"><div class="ico" aria-hidden="true">⎇</div><p>暂无提交</p><p>先绑定仓库并点击“加载 Commit”。</p></div>
        <div v-else class="list">
          <button v-for="c in commits" :key="c.commitId" class="list-row row-commits" :class="{ selected: selectedCommit && selectedCommit.commitId === c.commitId }" @click="selectCommit(c)">
            <span class="mono">{{ shortCommit(c.commitId) }}</span>
            <span class="grow">{{ c.message }}</span>
            <span class="mono">{{ c.authorName }}</span>
          </button>
        </div>

        <template v-if="selectedCommit">
          <div class="actions">
            <button class="sm secondary" @click="run(loadDiff)" :disabled="busy.diff">
              <span v-if="busy.diff" class="spinner dark"></span>{{ diffFiles.length ? '刷新变更' : '查看变更' }}
            </button>
            <button class="sm" @click="reviewSelectedCommit">对该提交发起审查 →</button>
          </div>
          <div v-if="diffFiles.length">
            <div v-for="f in diffFiles" :key="f.filePath" class="diff-wrap">
              <div class="diff-file-head">
                <span class="fname">{{ f.filePath }}</span>
                <span class="adds">+{{ f.additions }}</span>
                <span class="dels">-{{ f.deletions }}</span>
              </div>
              <pre class="diff-body numbered"><code><span v-for="(ln, i) in diffLines(f.diff)" :key="i" class="diff-line" :class="ln.cls"><span class="ln-no" aria-hidden="true">{{ ln.oldNo }}</span><span class="ln-no" aria-hidden="true">{{ ln.newNo }}</span><span class="ln-text">{{ ln.text }}</span></span></code></pre>
            </div>
          </div>
        </template>
      </div>
  </div>
</template>

<script setup>
import { shortCommit } from '../utils/format.js'
import { diffLines } from '../utils/labels.js'
import { useBusy } from '../composables/useBusy.js'
import { useRepository } from '../composables/useRepository.js'
import { useWorkspace } from '../composables/useWorkspace.js'

const { busy, run } = useBusy()
const { repoForm, commits, selectedCommit, diffFiles, repoBound, needsToken, useDemoRepository, bindRepository, unbindRepository, loadCommits } = useRepository()
const { selectCommit, loadDiff, reviewSelectedCommit } = useWorkspace()
</script>
