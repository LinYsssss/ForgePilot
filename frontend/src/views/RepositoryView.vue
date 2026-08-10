<template>
  <div class="view">
    <el-card shadow="never">
      <template #header>
        <div class="rs-card-head">
          <div>
            <h2>仓库配置</h2>
            <p class="rs-sub">绑定 Git 仓库以拉取提交和 diff</p>
          </div>
          <span v-if="repoBound" class="status-pill st-ACTIVE">已绑定</span>
        </div>
      </template>
      <el-form label-position="top" @submit.prevent>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="8">
            <el-form-item label="Git 地址">
              <el-input v-model="repoForm.repoUrl" placeholder="https://… 或本地演示路径" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="8">
            <el-form-item label="Provider">
              <el-select v-model="repoForm.provider">
                <el-option label="GitHub" value="GITHUB" />
                <el-option label="GitLab" value="GITLAB" />
                <el-option label="Gitee" value="GITEE" />
                <el-option label="本地路径" value="LOCAL" />
                <el-option label="其他" value="OTHER" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="8">
            <el-form-item label="默认分支">
              <el-input v-model="repoForm.defaultBranch" placeholder="main" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item v-if="needsToken" label="访问令牌 (私有仓库需要，加密存储，不回显)">
          <el-input v-model="repoForm.accessToken" type="password" :placeholder="repoForm._tokenConfigured ? '已保存令牌，留空则沿用原令牌' : 'ghp_… / glpat-…'" />
        </el-form-item>
      </el-form>
      <el-space wrap :size="12">
        <el-button type="primary" :loading="busy.bind" :disabled="busy.bind" @click="run(bindRepository)">{{ repoBound ? '更新绑定' : '绑定仓库' }}</el-button>
        <el-button @click="useDemoRepository">填入演示仓库</el-button>
        <el-button :loading="busy.commits" :disabled="!repoBound || busy.commits" @click="run(loadCommits)">加载 Commit</el-button>
        <el-button v-if="repoBound" type="danger" plain @click="run(unbindRepository)">解绑</el-button>
      </el-space>
      <p class="rs-hint" v-if="needsToken">⚠ 远程私有仓库请填写访问令牌；公开仓库或本地路径可留空。</p>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="rs-card-head">
          <div>
            <h2>提交历史</h2>
            <p class="rs-sub">点击某次提交查看变更明细</p>
          </div>
          <el-tag v-if="selectedCommit" type="info" class="mono rs-picked">已选 {{ shortCommit(selectedCommit.commitId) }}</el-tag>
        </div>
      </template>
      <el-skeleton v-if="busy.commits" :rows="4" animated />
      <el-empty v-else-if="!commits.length" description="暂无提交" :image-size="88">
        <p class="rs-empty-sub">先绑定仓库并点击“加载 Commit”。</p>
      </el-empty>
      <el-table
        v-else
        class="rs-commits"
        :data="commits"
        row-key="commitId"
        highlight-current-row
        :row-style="{ cursor: 'pointer' }"
        @row-click="row => selectCommit(row)"
      >
        <el-table-column label="提交" width="112">
          <template #default="{ row }"><span class="mono">{{ shortCommit(row.commitId) }}</span></template>
        </el-table-column>
        <el-table-column label="消息" prop="message" min-width="280" show-overflow-tooltip />
        <el-table-column label="作者" width="180" align="right" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.authorName }}</span></template>
        </el-table-column>
      </el-table>

      <template v-if="selectedCommit">
        <el-space wrap :size="12" class="rs-actions">
          <el-button size="small" :loading="busy.diff" :disabled="busy.diff" @click="run(loadDiff)">{{ diffFiles.length ? '刷新变更' : '查看变更' }}</el-button>
          <el-button size="small" type="primary" @click="reviewSelectedCommit">对该提交发起审查 →</el-button>
        </el-space>
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
    </el-card>
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

<style scoped>
/* Precision Workbench 仓库工作台:Element 结构 + tokens 直供。
   scoped 同名重定义清单(脱离 styles.css 的 Observatory 视觉,收尾删除
   styles.css 后本页不受影响):.status-pill/.st-ACTIVE、.mono、
   .diff-wrap/.diff-file-head(.fname/.adds/.dels)/.diff-body(.numbered)/
   .diff-line(.add/.del/.hunk/.meta)/.ln-no/.ln-text */
.view {
  display: grid;
  gap: var(--sp-5);
  align-content: start;
}

/* ---- 卡片头:标题 + 说明 + 右侧徽标 ---- */
.rs-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.rs-card-head h2 {
  margin: 0;
  font-family: var(--rs-font-body);
  font-size: var(--rs-fs-lg);
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--rs-text);
}
.rs-sub {
  margin: 3px 0 0;
  font-size: var(--rs-fs-sm);
  font-weight: 400;
  color: var(--rs-text-dim);
}

/* 「已选 xxxxxxxx」徽标:el-tag 供形,提对比度至 AA(info 灰面上偏浅)。
   前挂 .rs-card-head 抬到 (0,3,0):稳压 .el-tag.el-tag--info 的变量
   定义,与 CSS 打包顺序无关(同 tokens.css 的 html:root 思路)。 */
.rs-card-head .rs-picked {
  --el-tag-text-color: var(--rs-text-soft);
  font-weight: 600;
}

/* ---- 「已绑定」状态 pill(同名重定义;圆点形状由宿主类提供,配色对齐
   tokens 的 st-* 绿组) ---- */
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 3px 10px;
  border: 1px solid transparent;
  border-radius: var(--rs-radius-round);
  font-size: var(--rs-fs-xs);
  font-weight: 700;
  line-height: 1.6;
  white-space: nowrap;
}
.status-pill::before {
  content: "";
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}
.st-ACTIVE {
  color: var(--rs-risk-low-text);
  background: var(--rs-risk-low-bg);
  border-color: var(--rs-risk-low-border);
}

.mono {
  font-family: var(--rs-font-mono);
  font-feature-settings: "liga" 0;
}

.rs-hint {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin: var(--sp-3) 0 0;
  font-size: var(--rs-fs-sm);
  line-height: 1.5;
  color: var(--rs-text-soft);
}

.rs-empty-sub {
  margin: 0;
  font-size: var(--rs-fs-sm);
  color: var(--rs-text-dim);
}

/* ---- 提交表格:选中行走 Element 官方变量;mono 列沿用原视觉层级 ---- */
.rs-commits {
  --el-table-text-color: var(--rs-text);
  --el-table-current-row-bg-color: var(--el-color-primary-light-9);
}
.rs-commits .mono {
  font-size: var(--rs-fs-sm);
  color: var(--rs-text-soft);
}

.rs-actions {
  margin-top: var(--sp-5);
}

/* ---- commit diff 查看器:解析与模板结构不动,亮色码面重铸
   (--rs-diff-* 方案;滚动容器 overflow-x 行为保留) ---- */
.diff-wrap {
  margin-top: var(--sp-3);
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-sm);
  overflow: hidden;
}
.diff-file-head {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: 9px 14px;
  background: var(--rs-fill-lighter);
  border-bottom: 1px solid var(--rs-border);
  font-size: var(--rs-fs-sm);
  color: var(--rs-text-soft);
}
.diff-file-head .fname {
  font-family: var(--rs-font-mono);
  font-weight: 600;
  word-break: break-all;
  color: var(--rs-text);
}
.diff-file-head .adds {
  color: var(--rs-risk-low-text);
  font-weight: 700;
  font-family: var(--rs-font-mono);
}
.diff-file-head .dels {
  color: var(--rs-risk-high-text);
  font-weight: 700;
  font-family: var(--rs-font-mono);
}
.diff-body {
  margin: 0;
  padding: var(--sp-2) 0;
  font-family: var(--rs-font-mono);
  font-size: var(--rs-fs-sm);
  line-height: 1.6;
  overflow-x: auto;
  background: var(--rs-diff-surface);
  color: var(--rs-text);
  scrollbar-color: var(--rs-border-strong) transparent;
}
.diff-line {
  display: block;
  padding: 0 14px;
  white-space: pre;
  color: var(--rs-text-soft);
}
.diff-line.add { background: var(--rs-diff-add-bg); color: var(--rs-diff-add-text); }
.diff-line.del { background: var(--rs-diff-del-bg); color: var(--rs-diff-del-text); }
.diff-line.hunk { background: var(--rs-diff-hunk-bg); color: var(--rs-diff-hunk-text); }
.diff-line.meta { color: var(--rs-diff-meta); }

/* 带行号版本:旧/新两列行号 + 行 hover 微沉(亮色面向下压,替代暗色提亮) */
.diff-body.numbered .diff-line {
  display: grid;
  grid-template-columns: 44px 44px 1fr;
  padding: 0;
}
.diff-body.numbered .ln-no {
  text-align: right;
  padding-right: 10px;
  color: var(--rs-diff-lineno);
  user-select: none;
  border-right: 1px solid var(--rs-border-faint);
  font-size: var(--rs-fs-xs);
  line-height: inherit;
}
.diff-body.numbered .ln-text {
  padding: 0 14px;
  white-space: pre;
  overflow: hidden;
}
.diff-body.numbered .diff-line:hover { filter: brightness(0.97); }
.diff-body.numbered .diff-line.add .ln-no:nth-child(2),
.diff-body.numbered .diff-line.del .ln-no:first-child { color: inherit; opacity: 0.75; }
</style>
