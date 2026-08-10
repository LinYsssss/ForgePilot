<template>
  <div class="view">
    <el-card shadow="never">
      <template #header>
        <div class="card-head"><div><h2>上传知识文档</h2><div class="card-sub">支持 .md / .txt，用于 RAG 检索增强审查</div></div></div>
      </template>
      <el-form label-position="top" @submit.prevent>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="8">
            <el-form-item label="文档类型">
              <el-select v-model="docType">
                <el-option label="业务流程" value="BUSINESS_FLOW" />
                <el-option label="安全规范" value="SECURITY_POLICY" />
                <el-option label="README" value="README" />
                <el-option label="其他" value="OTHER" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="16">
            <!-- 文件控件保留原生 input:el-upload 的 on-change 回调是 (uploadFile, uploadFiles),
                 不是 DOM 事件,喂不进 onFileChange(e.target.files);auto-upload/file-list 生命周期
                 也与「选文件 → 点上传」的 composable 契约不合。数据流红线优先,外观用 tokens 重铸。
                 el-form-item 的 for 显式关联 label(可达性 AC)。 -->
            <el-form-item label="文档文件" for="kb-file-input">
              <input id="kb-file-input" class="rs-file" type="file" accept=".md,.txt" @change="onFileChange" />
            </el-form-item>
          </el-col>
        </el-row>
        <div class="form-actions">
          <el-button type="primary" :loading="busy.upload" :disabled="busy.upload" @click="run(uploadDocument)">上传并入库</el-button>
        </div>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <div><h2>已入库文档</h2><div class="card-sub">共 {{ documents.length }} 篇</div></div>
          <el-button size="small" :loading="busy.reindex" :disabled="!documents.length || busy.reindex" @click="run(reindexKnowledge, 'reindex')">重建索引</el-button>
        </div>
      </template>
      <el-empty v-if="!documents.length" description="知识库为空"><p class="empty-extra">上传业务流程或安全规范文档。</p></el-empty>
      <div v-else class="doc-grid">
        <div v-for="d in documents" :key="d.documentId" class="doc-card">
          <div class="doc-name">📄 {{ d.fileName }}</div>
          <div class="doc-foot">
            <span class="badge plain">{{ d.docType }}</span>
            <span class="status-pill" :class="'st-' + d.status">{{ d.status }}</span>
          </div>
          <el-button size="small" type="danger" plain @click="run(() => deleteDocument(d.documentId))">删除</el-button>
        </div>
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-head"><div><h2>检索测试</h2><div class="card-sub">验证语义召回效果</div></div></div>
      </template>
      <div class="rs-search-row">
        <el-input v-model="searchQuery" class="rs-query" placeholder="发货前是否需要校验支付状态" @keyup.enter="run(searchKnowledge)" />
        <el-button type="primary" :loading="busy.search" :disabled="busy.search" @click="run(searchKnowledge)">检索</el-button>
      </div>
      <div v-if="searchMatches.length" class="match-list">
        <div v-for="m in searchMatches" :key="m.chunkId" class="match">
          <div class="match-head">
            <span class="src">{{ m.sourceName }} #{{ m.chunkIndex }}</span>
            <span class="score">相似度 {{ (m.score * 100).toFixed(1) }}%</span>
          </div>
          <div class="match-body">{{ m.content }}</div>
        </div>
      </div>
      <el-empty v-else-if="searched" description="未检索到相关内容" :image-size="88" />
    </el-card>
  </div>
</template>

<script setup>
import { useBusy } from '../composables/useBusy.js'
import { useKnowledge } from '../composables/useKnowledge.js'

const { busy, run } = useBusy()
const { documents, docType, searchQuery, searchMatches, searched, onFileChange, uploadDocument, reindexKnowledge, deleteDocument, searchKnowledge } = useKnowledge()
</script>

<style scoped>
/* Precision Workbench 知识库:Element 结构 + tokens 直供。
   scoped 同名重定义清单(styles.css 冻结期间由本段供视觉,收尾删除后不受影响):
   .doc-grid/.doc-card(.doc-name/.doc-foot)、.badge(.plain)、
   .status-pill/.st-* 四组、.match/.match-head(.src/.score)/.match-body。
   改名/替换:.panel→el-card、.grid.three/.field→el-form 栅格、.actions→.form-actions、
   .fb-row→.rs-search-row(避开 styles.css 的 .fb-row input/select 后代规则)、
   .empty/.ico→el-empty、.spinner→el-button :loading。 */
.view {
  display: grid;
  gap: var(--sp-5);
  align-content: start;
  font-family: var(--rs-font-body);
  color: var(--rs-text);
}

/* ---- 卡头(与 ProjectsView 同构;h2 显式定字族,躲开 styles.css
   元素级 --font-display 泄漏) ---- */
.card-head { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); flex-wrap: wrap; }
.card-head h2 { margin: 0; font-family: var(--rs-font-body); font-size: var(--rs-fs-lg); font-weight: 700; color: var(--rs-text); }
.card-sub { margin-top: 3px; color: var(--rs-text-dim); font-size: var(--rs-fs-sm); font-weight: 400; }

.form-actions { display: flex; gap: var(--sp-3); }

.empty-extra { margin: 0; color: var(--rs-text-dim); font-size: var(--rs-fs-sm); }

/* ---- 原生文件控件 tokens 重铸(全属性覆写,不依赖 styles.css 的
   input/input[type=file] 全局规则;高度对齐 Element 32px 控件) ---- */
.rs-file {
  width: 100%;
  height: 32px;
  padding: 2px 12px;
  background: var(--rs-surface);
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-base);
  color: var(--rs-text-soft);
  font-family: var(--rs-font-body);
  font-size: var(--rs-fs-sm);
  cursor: pointer;
  transition: border-color var(--rs-t-fast) var(--rs-ease-out), box-shadow var(--rs-t-fast) var(--rs-ease-out);
}
.rs-file:hover { border-color: var(--rs-border-strong); }
.rs-file:focus {
  outline: none;
  border-color: var(--rs-primary);
  box-shadow: 0 0 0 3px var(--el-color-primary-light-8);
  background: var(--rs-surface);
}
.rs-file::file-selector-button {
  margin-right: var(--sp-3);
  padding: 2px 10px;
  background: var(--rs-fill);
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-sm);
  color: var(--rs-text);
  font-family: var(--rs-font-body);
  font-size: var(--rs-fs-sm);
  font-weight: 600;
  cursor: pointer;
  transition: background var(--rs-t-fast), border-color var(--rs-t-fast), color var(--rs-t-fast);
}
.rs-file::file-selector-button:hover {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-5);
  color: var(--rs-primary);
}

/* ---- 文档卡网格:tokens 手铸白卡(参考 ProjectsView 项目卡手法;
   删除按钮为纵向 flex 直接子元素,沿用原版通栏拉伸布局) ---- */
.doc-grid { display: grid; gap: var(--sp-3); grid-template-columns: repeat(auto-fill, minmax(232px, 1fr)); }

.doc-card {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding: var(--sp-4);
  background: var(--rs-surface);
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-base);
  color: var(--rs-text);
  transition: border-color var(--rs-t-base) var(--rs-ease-out), box-shadow var(--rs-t-base) var(--rs-ease-out), transform var(--rs-t-base) var(--rs-ease-out);
}
.doc-card:hover { border-color: var(--el-color-primary-light-5); box-shadow: var(--rs-shadow-md); transform: translateY(-2px); }

.doc-card .doc-name {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--rs-fs-sm);
  font-weight: 700;
  word-break: break-all;
  color: var(--rs-text);
}
.doc-card .doc-foot { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-2); }

/* ---- docType 中性徽标(原 .badge.plain;补 1px 描边,padding 3px 10px
   → 2px 9px 外框尺寸不变) ---- */
.badge {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-1);
  padding: 2px 9px;
  border: 1px solid transparent;
  border-radius: var(--rs-radius-round);
  font-size: var(--rs-fs-xs);
  font-weight: 700;
  letter-spacing: 0.02em;
  white-space: nowrap;
  line-height: 1.5;
}
.badge.plain {
  background: var(--rs-fill);
  border-color: var(--rs-border);
  color: var(--rs-text-soft);
  font-family: var(--rs-font-mono);
  font-weight: 600;
}

/* ---- 状态 pill:st-* 同名 scoped 重定义,分组与 tokens.css @layer 工具类
   一致(styles.css 冻结期间由本段供色);padding 4px 11px → 3px 10px:
   补入 1px 描边后外框尺寸不变。 ---- */
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 3px 10px;
  border: 1px solid transparent;
  border-radius: var(--rs-radius-round);
  font-size: var(--rs-fs-xs);
  font-weight: 700;
  white-space: nowrap;
  line-height: 1.5;
}
.status-pill::before { content: ""; width: 7px; height: 7px; border-radius: 50%; background: currentColor; }

.st-SUCCESS, .st-INDEXED, .st-ACTIVE, .st-CONSUMED, .st-PUBLISHED, .st-PASSED, .st-OPEN, .st-MERGED {
  color: var(--rs-risk-low-text);
  background: var(--rs-risk-low-bg);
  border-color: var(--rs-risk-low-border);
}
.st-RUNNING, .st-PENDING, .st-WAIVED {
  color: var(--rs-risk-medium-text);
  background: var(--rs-risk-medium-bg);
  border-color: var(--rs-risk-medium-border);
}
.st-FAILED, .st-DEAD, .st-ERROR, .st-CHANGES_REQUESTED {
  color: var(--rs-risk-high-text);
  background: var(--rs-risk-high-bg);
  border-color: var(--rs-risk-high-border);
}
.st-CANCELED, .st-CLOSED {
  color: var(--rs-risk-none-text);
  background: var(--rs-risk-none-bg);
  border-color: var(--rs-risk-none-border);
}

/* ---- 检索行:输入撑满 + 按钮,替代 .fb-row(改名避开全局后代规则) ---- */
.rs-search-row { display: flex; gap: var(--sp-2); align-items: flex-start; flex-wrap: wrap; }
.rs-search-row .rs-query { flex: 1; min-width: 180px; }

/* ---- 检索结果卡:来源+分数+片段 信息结构不变,亮色码面层次
   (score 徽标 teal 语义,--el-color-primary 系;light-9 底上主色 4.69:1 过 AA) ---- */
.match-list { margin-top: var(--sp-4); }

.match {
  padding: var(--sp-4);
  background: var(--rs-fill-lighter);
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-sm);
  margin-bottom: var(--sp-3);
}
.match:last-child { margin-bottom: 0; }

.match-head { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-2); margin-bottom: var(--sp-2); }
.match-head .src {
  font-family: var(--rs-font-mono);
  font-size: var(--rs-fs-sm);
  font-weight: 700;
  color: var(--rs-text);
  word-break: break-all;
}
.match-head .score {
  padding: 2px 9px;
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: var(--rs-radius-round);
  color: var(--el-color-primary);
  font-size: var(--rs-fs-xs);
  font-weight: 700;
  white-space: nowrap;
}
.match-body {
  color: var(--rs-text-soft);
  font-size: var(--rs-fs-sm);
  line-height: 1.6;
  white-space: pre-wrap;
  max-height: 130px;
  overflow: auto;
  scrollbar-color: var(--rs-border-strong) transparent;
}
</style>
