<template>
    <div class="panel">
      <div class="panel-head"><div><h2>上传知识文档</h2><div class="sub">支持 .md / .txt，用于 RAG 检索增强审查</div></div></div>
      <div class="grid three">
        <label class="field">文档类型
          <select v-model="docType">
            <option value="BUSINESS_FLOW">业务流程</option>
            <option value="SECURITY_POLICY">安全规范</option>
            <option value="README">README</option>
            <option value="OTHER">其他</option>
          </select>
        </label>
        <label class="field" style="grid-column: span 2">文档文件<input type="file" accept=".md,.txt" @change="onFileChange" /></label>
      </div>
      <div class="actions">
        <button @click="run(uploadDocument)" :disabled="busy.upload">
          <span v-if="busy.upload" class="spinner"></span>上传并入库
        </button>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <div><h2>已入库文档</h2><div class="sub">共 {{ documents.length }} 篇</div></div>
        <button class="sm secondary" :disabled="!documents.length || busy.reindex" @click="run(reindexKnowledge, 'reindex')">
          <span v-if="busy.reindex" class="spinner dark"></span>重建索引
        </button>
      </div>
      <div v-if="!documents.length" class="empty"><div class="ico" aria-hidden="true">▣</div><p>知识库为空</p><p>上传业务流程或安全规范文档。</p></div>
      <div v-else class="doc-grid">
        <div v-for="d in documents" :key="d.documentId" class="doc-card">
          <div class="doc-name">📄 {{ d.fileName }}</div>
          <div class="doc-foot">
            <span class="badge plain">{{ d.docType }}</span>
            <span class="status-pill" :class="'st-' + d.status">{{ d.status }}</span>
          </div>
          <button class="danger sm" @click="run(() => deleteDocument(d.documentId))">删除</button>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head"><div><h2>检索测试</h2><div class="sub">验证语义召回效果</div></div></div>
      <div class="fb-row">
        <input v-model="searchQuery" placeholder="发货前是否需要校验支付状态" @keyup.enter="run(searchKnowledge)" />
        <button @click="run(searchKnowledge)" :disabled="busy.search">
          <span v-if="busy.search" class="spinner"></span>检索
        </button>
      </div>
      <div v-if="searchMatches.length" style="margin-top:16px">
        <div v-for="m in searchMatches" :key="m.chunkId" class="match">
          <div class="match-head">
            <span class="src">{{ m.sourceName }} #{{ m.chunkIndex }}</span>
            <span class="score">相似度 {{ (m.score * 100).toFixed(1) }}%</span>
          </div>
          <div class="match-body">{{ m.content }}</div>
        </div>
      </div>
      <div v-else-if="searched" class="empty"><div class="ico" aria-hidden="true">🔍</div><p>未检索到相关内容</p></div>
    </div>
</template>

<script setup>
import { useBusy } from '../composables/useBusy'
import { useKnowledge } from '../composables/useKnowledge'

const { busy, run } = useBusy()
const { documents, docType, searchQuery, searchMatches, searched, onFileChange, uploadDocument, reindexKnowledge, deleteDocument, searchKnowledge } = useKnowledge()
</script>
