<template>
  <main class="app-shell">
    <aside class="sidebar">
      <div>
        <h1>RepoSage</h1>
        <p>Java + MQ + RAG + AI</p>
      </div>
      <nav>
        <button :class="{ active: tab === 'projects' }" @click="tab = 'projects'">项目</button>
        <button :class="{ active: tab === 'knowledge' }" @click="tab = 'knowledge'" :disabled="!activeProject">知识库</button>
        <button :class="{ active: tab === 'repository' }" @click="tab = 'repository'" :disabled="!activeProject">仓库</button>
        <button :class="{ active: tab === 'reviews' }" @click="tab = 'reviews'" :disabled="!activeProject">审查</button>
        <button :class="{ active: tab === 'aiLogs' }" @click="openProjectAiLogs" :disabled="!activeProject">AI日志</button>
      </nav>
      <button class="ghost" @click="logout" v-if="token">退出登录</button>
    </aside>

    <section class="content">
      <header class="topbar">
        <div>
          <strong>{{ activeProject ? activeProject.name : '请选择项目' }}</strong>
          <span v-if="activeProject">默认分支 {{ activeProject.defaultBranch }}</span>
        </div>
        <button @click="run(refreshAll)" :disabled="!token">刷新</button>
      </header>

      <section v-if="!token" class="panel auth-panel">
        <h2>登录 / 注册</h2>
        <div class="grid two">
          <label>用户名<input v-model="auth.username" placeholder="developer" /></label>
          <label>密码<input v-model="auth.password" type="password" placeholder="123456" /></label>
        </div>
        <div class="actions">
          <button @click="run(login)">登录</button>
          <button class="secondary" @click="run(register)">注册并登录</button>
        </div>
      </section>

      <section v-else-if="tab === 'projects'" class="panel">
        <h2>项目管理</h2>
        <div class="grid three">
          <label>项目名称<input v-model="projectForm.name" placeholder="mall-order-service" /></label>
          <label>默认分支<input v-model="projectForm.defaultBranch" placeholder="main" /></label>
          <label>描述<input v-model="projectForm.description" placeholder="电商订单服务" /></label>
        </div>
        <div class="actions">
          <button @click="run(createProject)">创建项目</button>
        </div>
        <div class="table">
          <button v-for="project in projects" :key="project.projectId" class="row" @click="selectProject(project)">
            <span>{{ project.name }}</span>
            <span>{{ project.defaultBranch }}</span>
            <span>{{ project.status }}</span>
          </button>
        </div>
      </section>

      <section v-else-if="tab === 'repository'" class="panel">
        <h2>仓库配置</h2>
        <div class="grid three">
          <label>Git 地址<input v-model="repoForm.repoUrl" placeholder="本地路径或远程 Git URL" /></label>
          <label>Provider<input v-model="repoForm.provider" placeholder="OTHER" /></label>
          <label>默认分支<input v-model="repoForm.defaultBranch" placeholder="main" /></label>
        </div>
        <div class="actions">
          <button @click="run(bindRepository)">绑定仓库</button>
          <button class="secondary" @click="useDemoRepository">填入演示仓库</button>
          <button class="secondary" @click="run(loadCommits)">加载 Commit</button>
        </div>
        <div class="table">
          <button v-for="commit in commits" :key="commit.commitId" class="row" @click="selectCommit(commit)">
            <span>{{ shortCommit(commit.commitId) }}</span>
            <span>{{ commit.message }}</span>
            <span>{{ commit.authorName }}</span>
          </button>
        </div>
        <p v-if="selectedCommit" class="hint">已选择 {{ shortCommit(selectedCommit.commitId) }}，审查表单会自动使用该提交。</p>
      </section>

      <section v-else-if="tab === 'knowledge'" class="panel">
        <h2>RAG 知识库</h2>
        <div class="grid two">
          <label>文档类型<input v-model="docType" placeholder="BUSINESS_FLOW" /></label>
          <label>文档文件<input type="file" @change="onFileChange" /></label>
        </div>
        <div class="actions">
          <button @click="run(uploadDocument)">上传并入库</button>
        </div>
        <div class="grid two">
          <label>检索问题<input v-model="searchQuery" placeholder="发货前是否需要校验支付状态" /></label>
          <button @click="run(searchKnowledge)">测试检索</button>
        </div>
        <div class="cards">
          <article v-for="doc in documents" :key="doc.documentId">
            <strong>{{ doc.fileName }}</strong>
            <span>{{ doc.docType }} / {{ doc.status }}</span>
          </article>
        </div>
        <pre v-if="searchResult">{{ searchResult }}</pre>
      </section>

      <section v-else-if="tab === 'reviews'" class="panel">
        <h2>代码审查</h2>
        <div class="grid three">
          <label>Commit ID<input v-model="reviewForm.commitId" :placeholder="selectedCommit ? selectedCommit.commitId : '留空默认最新'" /></label>
          <label>Base Commit<input v-model="reviewForm.baseCommitId" placeholder="可选" /></label>
          <label>分支<input v-model="reviewForm.branch" placeholder="main" /></label>
        </div>
        <div class="actions">
          <button @click="run(createReview)">触发审查</button>
          <button class="secondary" @click="run(loadReviews)">刷新报告</button>
        </div>
        <div class="split">
          <div class="table">
            <button v-for="task in tasks" :key="task.taskId" class="row" @click="activeTask = task">
              <span>#{{ task.taskId }}</span>
              <span>{{ shortCommit(task.commitId) }}</span>
              <span>{{ task.status }}</span>
            </button>
          </div>
          <div class="table">
            <button v-for="report in reports" :key="report.reportId" class="row" @click="loadReport(report.reportId)">
              <span>#{{ report.reportId }}</span>
              <span>{{ report.overallRisk }}</span>
              <span>{{ report.issueCount }} issues</span>
            </button>
          </div>
        </div>
        <div class="actions" v-if="activeTask">
          <button class="secondary" @click="run(() => loadMqLogs(activeTask.taskId))">查看任务 #{{ activeTask.taskId }} MQ 日志</button>
          <button class="secondary" @click="run(() => openTaskAiLogs(activeTask.taskId))">查看任务 #{{ activeTask.taskId }} AI 日志</button>
        </div>
        <pre v-if="mqLogs.length">{{ JSON.stringify(mqLogs, null, 2) }}</pre>
        <section v-if="reportDetail" class="report">
          <h3>{{ reportDetail.summary }}</h3>
          <article v-for="issue in reportDetail.issues" :key="issue.issueId" class="issue">
            <strong>{{ issue.severity }} / {{ issue.category }}</strong>
            <h4>{{ issue.title }}</h4>
            <p>{{ issue.description }}</p>
            <p><b>证据：</b>{{ issue.evidence }}</p>
            <p><b>建议：</b>{{ issue.suggestion }}</p>
            <div class="actions">
              <button class="secondary" @click="run(() => submitFeedback(issue.issueId, 'TRUE_POSITIVE'))">标记真实问题</button>
              <button class="secondary" @click="run(() => submitFeedback(issue.issueId, 'FALSE_POSITIVE'))">标记误报</button>
              <button class="secondary" @click="run(() => submitFeedback(issue.issueId, 'NEED_DISCUSSION'))">需要讨论</button>
            </div>
          </article>
        </section>
      </section>

      <section v-else-if="tab === 'aiLogs'" class="panel">
        <h2>AI 调用日志</h2>
        <div class="actions">
          <button @click="run(openProjectAiLogs)">刷新项目日志</button>
          <button class="secondary" @click="selectedAiLog = null">收起详情</button>
        </div>
        <div class="table">
          <button v-for="log in aiLogs" :key="log.id" class="row log-row" @click="selectedAiLog = log">
            <span>{{ log.requestType }}</span>
            <span>{{ log.provider }} / {{ log.model }}</span>
            <span>{{ log.status }}</span>
            <span>{{ log.latencyMs }} ms</span>
            <span>{{ log.promptChars }} → {{ log.responseChars }}</span>
          </button>
        </div>
        <pre v-if="selectedAiLog">{{ JSON.stringify(selectedAiLog, null, 2) }}</pre>
      </section>

      <p v-if="message" class="message">{{ message }}</p>
    </section>
  </main>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api, clearToken, getToken, setToken } from './api/client'

const token = ref(getToken())
const tab = ref('projects')
const message = ref('')
const projects = ref([])
const activeProject = ref(null)
const commits = ref([])
const selectedCommit = ref(null)
const documents = ref([])
const tasks = ref([])
const reports = ref([])
const activeTask = ref(null)
const reportDetail = ref(null)
const mqLogs = ref([])
const aiLogs = ref([])
const selectedAiLog = ref(null)
const selectedFile = ref(null)
const searchResult = ref('')
const searchQuery = ref('发货前是否需要校验支付状态')
const docType = ref('BUSINESS_FLOW')

const auth = reactive({ username: 'developer', password: '123456' })
const projectForm = reactive({ name: 'mall-order-service', description: '电商订单服务', defaultBranch: 'main' })
const repoForm = reactive({ repoUrl: '', provider: 'OTHER', defaultBranch: 'main' })
const reviewForm = reactive({ commitId: '', baseCommitId: '', branch: 'main' })
const demoRepoPath = import.meta.env.VITE_DEMO_REPO_PATH || 'F:\\202605New\\demo-repos\\mall-order-service'

function notify(text) {
  message.value = text
  setTimeout(() => (message.value = ''), 3500)
}

async function run(action) {
  try {
    await action()
  } catch (error) {
    notify(error?.message || '操作失败')
  }
}

async function register() {
  const data = await api('/auth/register', { method: 'POST', body: JSON.stringify(auth) })
  setToken(data.token)
  token.value = data.token
  await refreshAll()
}

async function login() {
  const data = await api('/auth/login', { method: 'POST', body: JSON.stringify(auth) })
  setToken(data.token)
  token.value = data.token
  await refreshAll()
}

function logout() {
  clearToken()
  token.value = ''
  activeProject.value = null
}

async function refreshAll() {
  if (!token.value) return
  await loadProjects()
  if (activeProject.value) {
    await Promise.allSettled([loadDocuments(), loadCommits(), loadReviews(), loadAiLogs()])
  }
}

async function loadProjects() {
  projects.value = await api('/projects')
  if (!activeProject.value && projects.value.length) {
    activeProject.value = projects.value[0]
  }
}

async function createProject() {
  const project = await api('/projects', { method: 'POST', body: JSON.stringify(projectForm) })
  activeProject.value = project
  await loadProjects()
  notify('项目已创建')
}

function selectProject(project) {
  activeProject.value = project
  tab.value = 'repository'
  run(refreshAll)
}

function useDemoRepository() {
  repoForm.repoUrl = demoRepoPath
  repoForm.provider = 'LOCAL'
  repoForm.defaultBranch = activeProject.value?.defaultBranch || 'main'
}

function selectCommit(commit) {
  selectedCommit.value = commit
  reviewForm.commitId = commit.commitId
  reviewForm.baseCommitId = commit.parentCommitId || ''
  reviewForm.branch = activeProject.value?.defaultBranch || 'main'
  notify(`已选择 Commit ${shortCommit(commit.commitId)}`)
}

async function bindRepository() {
  await api(`/projects/${activeProject.value.projectId}/repository`, {
    method: 'POST',
    body: JSON.stringify(repoForm)
  })
  notify('仓库已绑定')
}

async function loadCommits() {
  commits.value = await api(`/projects/${activeProject.value.projectId}/repository/commits`)
}

function onFileChange(event) {
  selectedFile.value = event.target.files?.[0] || null
}

async function uploadDocument() {
  if (!selectedFile.value) return notify('请选择文档')
  const form = new FormData()
  form.append('file', selectedFile.value)
  await api(`/projects/${activeProject.value.projectId}/knowledge/documents?docType=${docType.value}`, {
    method: 'POST',
    body: form
  })
  await loadDocuments()
  notify('文档已入库')
}

async function loadDocuments() {
  documents.value = await api(`/projects/${activeProject.value.projectId}/knowledge/documents`)
}

async function searchKnowledge() {
  const data = await api(`/projects/${activeProject.value.projectId}/knowledge/search`, {
    method: 'POST',
    body: JSON.stringify({ query: searchQuery.value, topK: 5 })
  })
  searchResult.value = JSON.stringify(data, null, 2)
}

async function createReview() {
  const commitId = reviewForm.commitId || selectedCommit.value?.commitId || ''
  await api(`/projects/${activeProject.value.projectId}/reviews/tasks`, {
    method: 'POST',
    body: JSON.stringify({ ...reviewForm, commitId })
  })
  await loadReviews()
  activeTask.value = tasks.value[0] || null
  if (reports.value[0]) {
    await loadReport(reports.value[0].reportId)
  }
  notify('审查任务已创建')
}

async function loadReviews() {
  tasks.value = await api(`/projects/${activeProject.value.projectId}/reviews/tasks`)
  reports.value = await api(`/projects/${activeProject.value.projectId}/reviews/reports`)
}

async function loadReport(reportId) {
  reportDetail.value = await api(`/projects/${activeProject.value.projectId}/reviews/reports/${reportId}`)
}

async function loadMqLogs(taskId) {
  mqLogs.value = await api(`/mq/logs?taskId=${taskId}`)
}

async function loadAiLogs(taskId = null) {
  if (!activeProject.value) return
  const query = taskId
    ? `taskId=${taskId}&limit=50`
    : `projectId=${activeProject.value.projectId}&limit=50`
  aiLogs.value = await api(`/ai/logs?${query}`)
}

async function openProjectAiLogs() {
  if (!activeProject.value) return
  await loadAiLogs()
  tab.value = 'aiLogs'
}

async function openTaskAiLogs(taskId) {
  await loadAiLogs(taskId)
  tab.value = 'aiLogs'
}

async function submitFeedback(issueId, feedbackType) {
  await api(`/review-issues/${issueId}/feedback`, {
    method: 'POST',
    body: JSON.stringify({ feedbackType, comment: '前端演示反馈' })
  })
  notify('反馈已提交')
}

function shortCommit(commitId) {
  return commitId ? commitId.slice(0, 8) : '-'
}

onMounted(refreshAll)
</script>
