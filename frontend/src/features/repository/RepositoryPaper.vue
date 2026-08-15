<template>
  <div class="ink-repository">
    <section class="ink-panel ink-reveal">
      <header class="panel-head">
        <div>
          <span class="ink-eyebrow">仓库绑定</span>
          <h1>{{ repoBound ? '已绑定仓库' : '绑定 Git 仓库' }}</h1>
          <p class="panel-sub">绑定后即可拉取提交与 diff,作为审查的证据来源。</p>
        </div>
        <SealBadge v-if="repoBound" label="已绑定" tone="success" />
      </header>

      <form class="form-grid" @submit.prevent="$emit('bind')">
        <label class="span-2">
          <span>Git 地址</span>
          <input v-model="form.repoUrl" class="ink-field" placeholder="https://… 或本地演示路径" required />
        </label>
        <label>
          <span>Provider</span>
          <select v-model="form.provider" class="ink-field">
            <option value="GITHUB">GitHub</option>
            <option value="GITLAB">GitLab</option>
            <option value="GITEE">Gitee</option>
            <option value="LOCAL">本地路径</option>
            <option value="OTHER">其他</option>
          </select>
        </label>
        <label>
          <span>默认分支</span>
          <input v-model="form.defaultBranch" class="ink-field" placeholder="main" />
        </label>

        <!-- 令牌字段只在远程 http(s) 地址下出现:本地路径填令牌没有意义,
             且后端对 LOCAL 也不消费该字段。 -->
        <label v-if="needsToken" class="span-full">
          <span>访问令牌(私有仓库需要,加密存储,不回显)</span>
          <input
            v-model="form.accessToken"
            class="ink-field"
            type="password"
            autocomplete="off"
            :placeholder="form._tokenConfigured ? '同一地址留空则沿用已存令牌' : 'ghp_… / glpat-…'"
          />
        </label>

        <div class="form-actions span-full">
          <button class="ink-primary-button" type="submit" :disabled="binding">
            {{ binding ? '处理中…' : (repoBound ? '更新绑定' : '绑定仓库') }}
          </button>
          <button class="ink-outline-button" type="button" @click="$emit('demo')">填入演示仓库</button>
          <button
            class="ink-outline-button"
            type="button"
            :disabled="!repoBound || loadingCommits"
            @click="$emit('load-commits')"
          >{{ loadingCommits ? '加载中…' : '加载 Commit' }}</button>
          <button
            v-if="repoBound"
            class="ink-outline-button is-danger"
            type="button"
            @click="$emit('unbind')"
          >解绑</button>
        </div>
      </form>

      <p v-if="needsToken" class="ink-hint">远程私有仓库请填写访问令牌;公开仓库或本地路径可留空。</p>
    </section>

    <section class="ink-panel ink-reveal">
      <header class="panel-head">
        <div>
          <span class="ink-eyebrow">提交历史</span>
          <h2>{{ commits.length ? `共 ${commits.length} 次提交` : '提交历史' }}</h2>
          <p class="panel-sub">选中一次提交,查看它的变更明细或直接发起审查。</p>
        </div>
        <code v-if="selectedCommit" class="picked">已选 {{ shortCommit(selectedCommit.commitId) }}</code>
      </header>

      <p v-if="loadingCommits" class="ink-hint">正在取提交…</p>
      <p v-else-if="!commits.length" class="ink-hint">暂无提交——先绑定仓库,再点「加载 Commit」。</p>
      <!-- title 补的是旧表格 show-overflow-tooltip 的等价物:单行截断后完整文本只剩
           DOM 里那份(读屏仍读全),鼠标用户没有别的途径看到被截掉的提交消息。 -->
      <ul v-else v-list-nav class="commit-list">
        <li v-for="commit in commits" :key="commit.commitId">
          <button
            type="button"
            class="commit-row list-row"
            :class="{ 'is-active': isSelected(commit) }"
            :aria-current="isSelected(commit) ? 'true' : undefined"
            @click="$emit('select-commit', commit)"
          >
            <code class="commit-sha">{{ shortCommit(commit.commitId) }}</code>
            <span class="commit-msg" :title="commit.message">{{ commit.message }}</span>
            <span class="commit-author" :title="commit.authorName">{{ commit.authorName }}</span>
          </button>
        </li>
      </ul>

      <template v-if="selectedCommit">
        <div class="commit-actions">
          <button class="ink-outline-button" type="button" :disabled="loadingDiff" @click="$emit('load-diff')">
            {{ loadingDiff ? '读取中…' : (diffFiles.length ? '刷新变更' : '查看变更') }}
          </button>
          <button class="ink-primary-button" type="button" @click="$emit('review-commit')">对该提交发起审查 →</button>
        </div>

        <div v-for="file in diffFiles" :key="file.filePath" class="diff-block">
          <div class="diff-head">
            <span class="diff-path">{{ file.filePath }}</span>
            <span class="diff-adds">+{{ file.additions }}</span>
            <span class="diff-dels">-{{ file.deletions }}</span>
          </div>
          <div class="ink-diff" role="region" :aria-label="`${file.filePath} 的变更`" tabindex="0">
            <div
              v-for="(line, index) in diffLines(file.diff)"
              :key="index"
              class="diff-row"
              :class="line.cls"
            >
              <span class="diff-no" aria-hidden="true">{{ line.oldNo }}</span>
              <span class="diff-no" aria-hidden="true">{{ line.newNo }}</span>
              <code>{{ line.text }}</code>
            </div>
          </div>
        </div>
      </template>
    </section>
  </div>
</template>

<script setup>
import SealBadge from '../../shared/ui/SealBadge.vue'
import { shortCommit } from '../../utils/format.js'
import { diffLines } from '../../utils/labels.js'

// 墨境仓库页(实施步骤 8 第三页)。纯展示 + 事件上抛。
//
// 两处沿用既有实现而非重写:`diffLines` 与 `shortCommit` 都是旧页面用的同一个工具函数,
// diff 的解析规则(hunk 头取行号、meta/add/del 分类)因此零改动——这是 diff 呈现能与旧版
// 逐行一致的前提,也避免出现第二套 diff 解析。
//
// 提交列表由 el-table 的整行点击改为按钮列表:表格行不可聚焦,键盘用户无法选中提交,
// 而"选中提交"是发起审查的必经步骤。语义(点击即选中)不变。
// 列表最多 100 行(loadCommits 的 limit),因此沿用全局 v-list-nav 走 ↑/↓ roving focus,
// 与 RunCaseList/FindingLedger 同一套,不在组件内手写 keydown。
const props = defineProps({
  form: { type: Object, required: true },
  commits: { type: Array, default: () => [] },
  selectedCommit: { type: Object, default: null },
  diffFiles: { type: Array, default: () => [] },
  repoBound: { type: Boolean, default: false },
  needsToken: { type: Boolean, default: false },
  binding: { type: Boolean, default: false },
  loadingCommits: { type: Boolean, default: false },
  loadingDiff: { type: Boolean, default: false },
})
defineEmits(['bind', 'demo', 'load-commits', 'unbind', 'select-commit', 'load-diff', 'review-commit'])

function isSelected(commit) {
  return !!props.selectedCommit && props.selectedCommit.commitId === commit.commitId
}
</script>

<style scoped>
.ink-repository { display: grid; gap: var(--ink-sp-16); }

.ink-panel {
  padding: var(--ink-sp-24);
  background: var(--ink-wash-panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--ink-radius-paper);
  box-shadow: var(--ink-shadow-panel);
}

.panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--ink-sp-12); flex-wrap: wrap; }
.panel-head h1, .panel-head h2 { margin: var(--ink-sp-4) 0 0; font-family: var(--ink-font-display); color: var(--ink-strong); }
.panel-head h1 { font-size: var(--ink-fs-28); }
.panel-head h2 { font-size: var(--ink-fs-20); }
.panel-sub { margin: var(--ink-sp-4) 0 0; color: var(--ink-muted); font-size: var(--ink-fs-13); line-height: var(--ink-lh-body); }

.picked {
  padding: var(--ink-sp-4) var(--ink-sp-8);
  background: var(--surface-muted);
  border: 1px solid var(--line-soft);
  color: var(--ink-default);
  font-family: var(--ink-font-code);
  font-size: var(--ink-fs-12);
}

/* ---- 绑定表单 ---- */
.form-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--ink-sp-16); margin-top: var(--ink-sp-16); }
.form-grid label { display: grid; gap: var(--ink-sp-4); }
.form-grid label span { color: var(--ink-muted); font-size: var(--ink-fs-13); }
.span-2 { grid-column: span 2; }
.span-full { grid-column: 1 / -1; }
.form-actions { display: flex; flex-wrap: wrap; gap: var(--ink-sp-8); justify-content: flex-end; }

.ink-field {
  width: 100%;
  padding: var(--ink-sp-8) var(--ink-sp-12);
  background: var(--ink-wash-field);
  border: 1px solid var(--line-soft);
  border-radius: var(--ink-radius-control);
  color: var(--ink-default);
  font: inherit;
}
.ink-field:focus-visible { outline: 2px solid var(--mineral-cyan); outline-offset: 1px; border-color: var(--line-strong); }

.ink-hint { margin: var(--ink-sp-16) 0 0; color: var(--ink-muted); font-size: var(--ink-fs-13); line-height: var(--ink-lh-body); }

/* ---- 提交列表(按钮行,可聚焦) ---- */
.commit-list { list-style: none; margin: var(--ink-sp-16) 0 0; padding: 0; display: grid; gap: var(--ink-sp-4); }
.commit-row {
  width: 100%;
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr) 160px;
  align-items: baseline;
  align-content: center;                /* 单行内容被 44px 下限撑高后不贴顶 */
  gap: var(--ink-sp-12);
  min-height: 44px;                     /* 合同 §8 触控目标下限;行高本身只有约 39px */
  padding: var(--ink-sp-8) var(--ink-sp-12);
  background: var(--surface-paper);
  border: 1px solid var(--line-soft);
  border-radius: var(--ink-radius-control);
  color: var(--ink-default);
  font: inherit;
  text-align: left;
  transition: border-color var(--ink-t-hover) var(--ink-ease-out);
}
.commit-row:hover { border-color: var(--line-strong); }
.commit-row:focus-visible { outline: 2px solid var(--mineral-cyan); outline-offset: -2px; }
/* --ink-glow-cyan 是颜色不是阴影:必须自带偏移/扩散,否则整条 box-shadow 被判无效丢弃,
   选中态就只剩边框换色这一重编码。 */
.commit-row.is-active { border-color: var(--mineral-cyan); box-shadow: 0 0 0 3px var(--ink-glow-cyan); }
.commit-sha { font-family: var(--ink-font-code); font-size: var(--ink-fs-12); color: var(--ink-strong); }
.commit-msg { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: var(--ink-fs-13); }
.commit-author { color: var(--ink-muted); font-size: var(--ink-fs-12); text-align: right; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.commit-actions { display: flex; flex-wrap: wrap; gap: var(--ink-sp-8); margin-top: var(--ink-sp-16); }

/* ---- 变更明细(码面令牌;与 EvidenceDiff 同一套呈现规则) ---- */
.diff-block { margin-top: var(--ink-sp-16); border: 1px solid var(--line-strong); border-radius: var(--ink-radius-control); overflow: hidden; }
.diff-head {
  display: flex;
  align-items: center;
  gap: var(--ink-sp-12);
  padding: var(--ink-sp-8) var(--ink-sp-12);
  background: var(--code-gutter);
  border-bottom: 1px solid var(--line-strong);
  font-size: var(--ink-fs-12);
}
.diff-path { flex: 1; font-family: var(--ink-font-code); font-weight: 700; color: var(--ink-strong); word-break: break-all; }
.diff-adds { color: var(--success-ink); font-family: var(--ink-font-code); font-weight: 700; }
.diff-dels { color: var(--cinnabar); font-family: var(--ink-font-code); font-weight: 700; }

.ink-diff {
  overflow: auto;
  max-height: 420px;
  background: var(--code-surface);
  font-family: var(--ink-font-code);
  font-size: var(--ink-fs-12);
  line-height: var(--ink-lh-code);
  scrollbar-color: var(--line-strong) transparent;
}
.ink-diff:focus-visible { outline: 2px solid var(--mineral-cyan); outline-offset: -2px; }
.diff-row { display: grid; grid-template-columns: 42px 42px minmax(max-content, 1fr); white-space: pre; color: var(--code-text); }
.diff-no {
  padding: 2px var(--ink-sp-8) 2px 2px;
  color: var(--ink-muted);
  background: var(--code-gutter);
  border-right: 1px solid var(--line-soft);
  text-align: right;
  font-size: var(--ink-fs-12);
  line-height: inherit;
  user-select: none;
}
.diff-row code { padding: 2px var(--ink-sp-8); background: none; font-family: inherit; }
.diff-row.add { background: var(--code-added-bg); }
.diff-row.add code { color: var(--code-added-text); }
.diff-row.del { background: var(--code-removed-bg); }
.diff-row.del code { color: var(--code-removed-text); }
.diff-row.hunk { background: var(--surface-muted); }
.diff-row.hunk code, .diff-row.meta code { color: var(--ink-muted); }

@media (max-width: 880px) {
  .form-grid { grid-template-columns: 1fr; }
  .span-2 { grid-column: auto; }
  .commit-row { grid-template-columns: 84px minmax(0, 1fr); }
  .commit-author { grid-column: 2; text-align: left; }
}
</style>
