<template>
  <!-- ===================== AUTH ===================== -->
  <div v-if="!token" class="auth-wrap">
    <div class="auth-card">
      <div class="brand">
        <div class="brand-logo">R</div>
        <div><h1>RepoSage</h1></div>
      </div>
      <p class="auth-sub">AI 代码仓库智能审查平台</p>
      <div class="grid">
        <label class="field">用户名
          <input v-model="auth.username" placeholder="developer" @keyup.enter="run(login)" />
        </label>
        <label class="field">密码
          <input v-model="auth.password" type="password" placeholder="至少 6 位" @keyup.enter="run(login)" />
        </label>
      </div>
      <div class="actions">
        <button @click="run(login)" :disabled="busy.auth">
          <span v-if="busy.auth" class="spinner"></span>登录
        </button>
      </div>
      <p class="hint">账号由管理员分配，如需账号请联系管理员。</p>
    </div>
    <transition name="t"><div v-if="toast.text" class="toast" :class="toast.type">{{ toast.text }}</div></transition>
  </div>

  <!-- ===================== APP ===================== -->
  <main v-else class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-logo">R</div>
        <div>
          <h1>RepoSage</h1>
          <div class="tagline">Java · MQ · RAG · AI</div>
        </div>
      </div>

      <nav>
        <button :class="{ active: tab === 'dashboard' }" @click="tab = 'dashboard'">
          <span class="nav-ico">▦</span> 概览
        </button>
        <button :class="{ active: tab === 'projects' }" @click="tab = 'projects'">
          <span class="nav-ico">▤</span> 项目
        </button>
        <button :class="{ active: tab === 'repository' }" @click="goTab('repository')" :disabled="!activeProject">
          <span class="nav-ico">⎇</span> 仓库
        </button>
        <button :class="{ active: tab === 'knowledge' }" @click="goTab('knowledge')" :disabled="!activeProject">
          <span class="nav-ico">▣</span> 知识库
        </button>
        <button :class="{ active: tab === 'reviews' }" @click="goTab('reviews')" :disabled="!activeProject">
          <span class="nav-ico">✓</span> 审查
        </button>
        <button :class="{ active: tab === 'aiLogs' }" @click="openProjectAiLogs" :disabled="!activeProject">
          <span class="nav-ico">◷</span> AI 日志
        </button>
      </nav>

      <div class="sidebar-foot">
        <div class="user-chip">
          <div class="avatar">{{ (me.nickname || me.username || 'U').slice(0,1).toUpperCase() }}</div>
          <div>
            <div>{{ me.nickname || me.username }}</div>
            <div class="tagline">{{ me.role }}</div>
          </div>
        </div>
        <button class="ghost" @click="logout">退出登录</button>
      </div>
    </aside>

    <section class="content">
      <header class="topbar">
        <div class="crumb">
          <strong>{{ tabTitle }}</strong>
          <span v-if="activeProject" class="muted">/ {{ activeProject.name }} · 默认分支 {{ activeProject.defaultBranch }}</span>
        </div>
        <div class="topbar-actions">
          <button class="secondary" @click="run(refreshAll)" :disabled="busy.refresh">
            <span v-if="busy.refresh" class="spinner dark"></span>刷新
          </button>
        </div>
      </header>

      <!-- ============ DASHBOARD ============ -->
      <template v-if="tab === 'dashboard'">
        <div class="stat-grid">
          <div class="stat"><div class="label">项目总数</div><div class="value">{{ projects.length }}</div></div>
          <div class="stat"><div class="label">审查任务</div><div class="value">{{ tasks.length }}</div></div>
          <div class="stat"><div class="label">审查报告</div><div class="value">{{ reports.length }}</div></div>
          <div class="stat">
            <div class="label">高风险报告</div>
            <div class="value" :class="highRiskCount ? 'tinted-high' : 'tinted-ok'">{{ highRiskCount }}</div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <div><h2>最近审查报告</h2><div class="sub">{{ activeProject ? activeProject.name : '请选择项目' }}</div></div>
            <button class="sm secondary" @click="goTab('reviews')" :disabled="!activeProject">前往审查 →</button>
          </div>
          <div v-if="!activeProject" class="empty"><div class="ico">▤</div><p>还没有选择项目</p><p>去“项目”页创建或选择一个项目。</p></div>
          <div v-else-if="!reports.length" class="empty"><div class="ico">✓</div><p>暂无审查报告</p><p>绑定仓库并触发一次代码审查吧。</p></div>
          <div v-else class="list">
            <button v-for="r in reports.slice(0,6)" :key="r.reportId" class="list-row row-reports" @click="openReport(r.reportId)">
              <span class="mono">#{{ r.reportId }}</span>
              <span class="grow">{{ shortCommit(r.commitId) }} · {{ fmtTime(r.createdAt) }}</span>
              <span class="badge" :class="'risk-' + r.overallRisk">{{ r.overallRisk }}</span>
              <span class="mono">{{ r.issueCount }} 问题</span>
            </button>
          </div>
        </div>
      </template>

      <!-- ============ PROJECTS (CRUD) ============ -->
      <template v-else-if="tab === 'projects'">
        <div class="panel">
          <div class="panel-head">
            <div><h2>{{ projectForm.projectId ? '编辑项目' : '创建项目' }}</h2><div class="sub">管理你的代码审查项目</div></div>
            <button v-if="projectForm.projectId" class="sm secondary" @click="resetProjectForm">取消编辑</button>
          </div>
          <div class="grid three">
            <label class="field">项目名称<input v-model="projectForm.name" placeholder="mall-order-service" /></label>
            <label class="field">默认分支<input v-model="projectForm.defaultBranch" placeholder="main" /></label>
            <label class="field">描述<input v-model="projectForm.description" placeholder="电商订单服务" /></label>
          </div>
          <div class="actions">
            <button @click="run(saveProject)" :disabled="busy.project">
              <span v-if="busy.project" class="spinner"></span>{{ projectForm.projectId ? '保存修改' : '创建项目' }}
            </button>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head"><div><h2>我的项目</h2><div class="sub">共 {{ projects.length }} 个</div></div></div>
          <div v-if="busy.projects" class="loading-overlay"><span class="spinner dark"></span> 加载中…</div>
          <div v-else-if="!projects.length" class="empty"><div class="ico">▤</div><p>还没有项目</p><p>在上方创建你的第一个项目。</p></div>
          <div v-else class="proj-grid">
            <div v-for="p in projects" :key="p.projectId" class="proj-card" :class="{ active: activeProject && activeProject.projectId === p.projectId }" @click="selectProject(p)">
              <div class="meta">
                <span class="status-pill" :class="'st-' + p.status">{{ p.status }}</span>
                <div class="card-actions">
                  <button class="secondary sm" title="编辑" @click.stop="editProject(p)">编辑</button>
                  <button class="danger sm" title="删除" @click.stop="askDeleteProject(p)">删除</button>
                </div>
              </div>
              <h3>{{ p.name }}</h3>
              <div class="desc">{{ p.description || '暂无描述' }}</div>
              <div class="meta">
                <span class="mono" style="color:var(--text-dim);font-size:12.5px">⎇ {{ p.defaultBranch }}</span>
                <span style="color:var(--text-dim);font-size:12px">{{ fmtDate(p.createdAt) }}</span>
              </div>
            </div>
          </div>
        </div>
      </template>

      <!-- ============ REPOSITORY ============ -->
      <template v-else-if="tab === 'repository'">
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
          <label class="field" v-if="needsToken" style="margin-top:14px">
            访问令牌 (私有仓库需要，加密存储，不回显)
            <input v-model="repoForm.accessToken" type="password" placeholder="ghp_… / glpat-…" />
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
            <span v-if="selectedCommit" class="badge plain risk-NONE">已选 {{ shortCommit(selectedCommit.commitId) }}</span>
          </div>
          <div v-if="busy.commits" class="loading-overlay"><span class="spinner dark"></span> 拉取提交中…</div>
          <div v-else-if="!commits.length" class="empty"><div class="ico">⎇</div><p>暂无提交</p><p>先绑定仓库并点击“加载 Commit”。</p></div>
          <div v-else class="list">
            <button v-for="c in commits" :key="c.commitId" class="list-row row-commits" :class="{ selected: selectedCommit && selectedCommit.commitId === c.commitId }" @click="selectCommit(c)">
              <span class="mono">{{ shortCommit(c.commitId) }}</span>
              <span class="grow">{{ c.message }}</span>
              <span style="color:var(--text-dim);font-size:12px">{{ c.authorName }}</span>
            </button>
          </div>

          <template v-if="selectedCommit">
            <div class="actions">
              <button class="sm secondary" @click="run(loadDiff)" :disabled="busy.diff">
                <span v-if="busy.diff" class="spinner dark"></span>{{ diffFiles.length ? '刷新变更' : '查看变更' }}
              </button>
              <button class="sm" @click="reviewSelectedCommit">对该提交发起审查 →</button>
            </div>
            <div v-if="diffFiles.length" >
              <div v-for="f in diffFiles" :key="f.filePath" class="diff-wrap">
                <div class="diff-file-head">
                  <span class="fname">{{ f.filePath }}</span>
                  <span class="adds">+{{ f.additions }}</span>
                  <span class="dels">-{{ f.deletions }}</span>
                </div>
                <pre class="diff-body"><code><span v-for="(ln, i) in diffLines(f.diff)" :key="i" class="diff-line" :class="ln.cls">{{ ln.text }}</span></code></pre>
              </div>
            </div>
          </template>
        </div>
      </template>

      <!-- ============ KNOWLEDGE (CRUD) ============ -->
      <template v-else-if="tab === 'knowledge'">
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
          <div class="panel-head"><div><h2>已入库文档</h2><div class="sub">共 {{ documents.length }} 篇</div></div></div>
          <div v-if="!documents.length" class="empty"><div class="ico">▣</div><p>知识库为空</p><p>上传业务流程或安全规范文档。</p></div>
          <div v-else class="doc-grid">
            <div v-for="d in documents" :key="d.documentId" class="doc-card">
              <div class="doc-name">📄 {{ d.fileName }}</div>
              <div class="doc-foot">
                <span class="badge plain risk-NONE">{{ d.docType }}</span>
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
          <div v-else-if="searched" class="empty"><div class="ico">🔍</div><p>未检索到相关内容</p></div>
        </div>
      </template>

      <!-- ============ REVIEWS ============ -->
      <template v-else-if="tab === 'reviews'">
        <div class="panel">
          <div class="panel-head">
            <div><h2>发起代码审查</h2><div class="sub">留空 Commit 则默认审查最新提交</div></div>
            <span v-if="pollingActive" class="status-pill st-RUNNING">审查进行中…</span>
          </div>
          <div class="grid three">
            <label class="field">Commit ID<input v-model="reviewForm.commitId" :placeholder="selectedCommit ? shortCommit(selectedCommit.commitId) : '留空=最新'" /></label>
            <label class="field">Base Commit<input v-model="reviewForm.baseCommitId" placeholder="可选，留空=父提交" /></label>
            <label class="field">分支<input v-model="reviewForm.branch" :placeholder="activeProject ? activeProject.defaultBranch : 'main'" /></label>
          </div>

          <div class="kb-select">
            <div class="kb-head">
              <span class="section-title" style="margin:0">参与审查的知识库</span>
              <div class="kb-tools">
                <button class="sm secondary" @click="selectAllDocs" :disabled="!documents.length">全选</button>
                <button class="sm secondary" @click="clearDocs" :disabled="!documents.length">清空</button>
              </div>
            </div>
            <div v-if="!documents.length" class="hint">该项目暂无知识库文档；不选则审查仅基于代码 diff。可在“知识库”页上传。</div>
            <div v-else class="kb-chips">
              <label v-for="d in documents" :key="d.documentId" class="kb-chip" :class="{ on: chosenDocs.has(d.documentId) }">
                <input type="checkbox" :checked="chosenDocs.has(d.documentId)" @change="toggleDoc(d.documentId)" />
                <span class="kb-name">📄 {{ d.fileName }}</span>
                <span class="badge plain risk-NONE">{{ d.docType }}</span>
              </label>
            </div>
            <p class="hint">已选 {{ chosenDocs.size }} / {{ documents.length }} 篇。不选 = 使用全部知识库。</p>
          </div>

          <div class="actions">
            <button @click="run(createReview)" :disabled="busy.review">
              <span v-if="busy.review" class="spinner"></span>触发审查
            </button>
            <button class="secondary" @click="run(loadReviews)" :disabled="busy.reviews">刷新列表</button>
          </div>
        </div>

        <div class="split">
          <div class="panel">
            <div class="panel-head"><div><h2>审查任务</h2><div class="sub">{{ tasks.length }} 条</div></div></div>
            <div v-if="!tasks.length" class="empty"><div class="ico">✓</div><p>暂无任务</p></div>
            <div v-else class="list">
              <button v-for="t in tasks" :key="t.taskId" class="list-row row-tasks" :class="{ selected: activeTask && activeTask.taskId === t.taskId }" @click="selectTask(t)">
                <span class="mono">#{{ t.taskId }}</span>
                <span class="grow mono">{{ shortCommit(t.commitId) }}</span>
                <span class="status-pill" :class="'st-' + t.status">{{ t.status }}</span>
              </button>
            </div>
          </div>
          <div class="panel">
            <div class="panel-head"><div><h2>审查报告</h2><div class="sub">{{ reports.length }} 条</div></div></div>
            <div v-if="!reports.length" class="empty"><div class="ico">▦</div><p>暂无报告</p></div>
            <div v-else class="list">
              <button v-for="r in reports" :key="r.reportId" class="list-row row-reports" :class="{ selected: reportDetail && reportDetail.reportId === r.reportId }" @click="openReport(r.reportId)">
                <span class="mono">#{{ r.reportId }}</span>
                <span class="grow">{{ r.issueCount }} 个问题</span>
                <span class="badge" :class="'risk-' + r.overallRisk">{{ r.overallRisk }}</span>
                <span style="color:var(--text-dim);font-size:12px">{{ fmtTime(r.createdAt) }}</span>
              </button>
            </div>
          </div>
        </div>

        <div class="panel" v-if="activeTask">
          <div class="panel-head">
            <div><h2>任务 #{{ activeTask.taskId }} 详情</h2><div class="sub">{{ activeTask.status }} · {{ shortCommit(activeTask.commitId) }}</div></div>
            <div class="topbar-actions">
              <button class="sm secondary" @click="run(() => loadMqLogs(activeTask.taskId))">MQ 日志</button>
              <button class="sm secondary" @click="run(() => openTaskAiLogs(activeTask.taskId))">AI 日志</button>
            </div>
          </div>
          <p v-if="activeTask.errorMessage" class="badge plain risk-HIGH" style="display:block;padding:10px">错误：{{ activeTask.errorMessage }}</p>
          <div v-if="mqLogs.length" class="list">
            <div v-for="(l, i) in mqLogs" :key="i" class="list-row" style="grid-template-columns: 110px 1fr auto; cursor:default">
              <span class="status-pill" :class="'st-' + mqStatusClass(l.status)">{{ l.status }}</span>
              <span class="grow mono">{{ l.routingKey }}</span>
              <span style="color:var(--text-dim);font-size:12px">重试 {{ l.retryCount }}</span>
            </div>
          </div>
        </div>

        <div class="panel" v-if="reportDetail">
          <div class="panel-head">
            <div><h2>{{ reportDetail.summary }}</h2><div class="sub">报告 #{{ reportDetail.reportId }} · {{ shortCommit(reportDetail.commitId) }}</div></div>
            <span class="badge" :class="'risk-' + reportDetail.overallRisk">总体风险 {{ reportDetail.overallRisk }}</span>
          </div>
          <div v-if="!reportDetail.issues.length" class="empty"><div class="ico">✓</div><p>未发现明显风险</p></div>
          <div v-for="issue in reportDetail.issues" :key="issue.issueId" class="issue" :class="'sevbar-' + issue.severity">
            <div class="issue-head">
              <span class="badge" :class="'sev-' + issue.severity">{{ issue.severity }}</span>
              <span class="badge plain risk-NONE">{{ issue.category }}</span>
              <h4>{{ issue.title }}</h4>
              <span v-if="issue.filePath" class="file">{{ issue.filePath }}<template v-if="issue.lineStart">:{{ issue.lineStart }}</template></span>
            </div>
            <p>{{ issue.description }}</p>
            <div class="kv" v-if="issue.impact"><b>影响</b><span>{{ issue.impact }}</span></div>
            <div class="kv" v-if="issue.evidence"><b>证据</b><span>{{ issue.evidence }}</span></div>
            <div class="kv"><b>建议</b><span>{{ issue.suggestion }}</span></div>
            <div class="conf" v-if="issue.confidence != null">
              置信度
              <span class="conf-bar"><span :style="{ width: Math.round(issue.confidence*100) + '%' }"></span></span>
              {{ Math.round(issue.confidence*100) }}%
            </div>

            <div class="issue-foot">
              <button class="sm secondary" @click="run(() => submitFeedback(issue.issueId, 'TRUE_POSITIVE'))">👍 真实问题</button>
              <button class="sm secondary" @click="run(() => submitFeedback(issue.issueId, 'FALSE_POSITIVE'))">🚫 误报</button>
              <button class="sm secondary" @click="run(() => submitFeedback(issue.issueId, 'NEED_DISCUSSION'))">💬 需讨论</button>
              <button class="sm secondary" @click="toggleFeedback(issue.issueId)">{{ openFeedback[issue.issueId] ? '收起反馈' : '查看反馈' }}</button>
            </div>

            <div v-if="openFeedback[issue.issueId]">
              <div class="fb-row">
                <select :value="ensureDraft(issue.issueId).type" @change="e => ensureDraft(issue.issueId).type = e.target.value">
                  <option value="TRUE_POSITIVE">真实问题</option>
                  <option value="FALSE_POSITIVE">误报</option>
                  <option value="NEED_DISCUSSION">需讨论</option>
                </select>
                <input :value="ensureDraft(issue.issueId).comment" @input="e => ensureDraft(issue.issueId).comment = e.target.value" placeholder="补充说明（可选）" @keyup.enter="run(() => submitFeedbackForm(issue.issueId))" />
                <button class="sm" @click="run(() => submitFeedbackForm(issue.issueId))">提交</button>
              </div>
              <div class="fb-list" v-if="(feedbackMap[issue.issueId] || []).length">
                <div v-for="fb in feedbackMap[issue.issueId]" :key="fb.feedbackId" class="fb-item">
                  <span class="badge plain" :class="fbBadge(fb.feedbackType)">{{ fbLabel(fb.feedbackType) }}</span>
                  <span class="who">用户#{{ fb.userId }}</span>
                  <span class="grow">{{ fb.comment || '—' }}</span>
                  <span style="color:var(--text-dim);font-size:11.5px">{{ fmtTime(fb.createdAt) }}</span>
                </div>
              </div>
              <div v-else class="hint">还没有反馈记录。</div>
            </div>
          </div>
        </div>
      </template>

      <!-- ============ AI LOGS ============ -->
      <template v-else-if="tab === 'aiLogs'">
        <div class="panel">
          <div class="panel-head">
            <div><h2>AI 调用日志</h2><div class="sub">{{ aiLogScope }}</div></div>
            <button class="sm secondary" @click="run(openProjectAiLogs)">刷新项目日志</button>
          </div>
          <div v-if="!aiLogs.length" class="empty"><div class="ico">◷</div><p>暂无调用日志</p><p>执行一次审查或检索后会生成。</p></div>
          <div v-else class="list">
            <button v-for="l in aiLogs" :key="l.id" class="list-row row-ailog" :class="{ selected: selectedAiLog && selectedAiLog.id === l.id }" @click="selectedAiLog = l">
              <span class="badge plain risk-NONE">{{ l.requestType }}</span>
              <span class="grow mono hide-sm">{{ l.provider }} / {{ l.model }}</span>
              <span class="status-pill" :class="'st-' + l.status">{{ l.status }}</span>
              <span class="mono hide-sm">{{ l.latencyMs }}ms</span>
              <span class="mono hide-sm">{{ l.promptChars }}→{{ l.responseChars }}</span>
            </button>
          </div>
        </div>
        <div class="panel" v-if="selectedAiLog">
          <div class="panel-head"><div><h2>调用详情 #{{ selectedAiLog.id }}</h2></div><button class="sm secondary" @click="selectedAiLog = null">收起</button></div>
          <div class="grid two">
            <div class="kv"><b>类型</b><span>{{ selectedAiLog.requestType }}</span></div>
            <div class="kv"><b>Provider</b><span>{{ selectedAiLog.provider }}</span></div>
            <div class="kv"><b>模型</b><span>{{ selectedAiLog.model }}</span></div>
            <div class="kv"><b>耗时</b><span>{{ selectedAiLog.latencyMs }} ms</span></div>
            <div class="kv"><b>输入</b><span>{{ selectedAiLog.promptChars }} 字符</span></div>
            <div class="kv"><b>输出</b><span>{{ selectedAiLog.responseChars }} 字符</span></div>
          </div>
          <p v-if="selectedAiLog.errorMessage" class="badge plain risk-HIGH" style="display:block;padding:10px;margin-top:10px">{{ selectedAiLog.errorMessage }}</p>
        </div>
      </template>
    </section>

    <!-- delete confirm modal -->
    <transition name="t">
      <div v-if="deleteTarget" class="modal-backdrop" @click.self="deleteTarget = null">
        <div class="modal">
          <h3>删除项目「{{ deleteTarget.name }}」？</h3>
          <p style="color:var(--text-soft)">将级联删除该项目的仓库绑定、知识库、审查任务、报告、问题与反馈，操作不可恢复。</p>
          <div class="actions">
            <button class="secondary" @click="deleteTarget = null">取消</button>
            <button class="danger" @click="run(confirmDeleteProject)" :disabled="busy.delete">
              <span v-if="busy.delete" class="spinner dark"></span>确认删除
            </button>
          </div>
        </div>
      </div>
    </transition>

    <transition name="t"><div v-if="toast.text" class="toast" :class="toast.type">{{ toast.text }}</div></transition>
  </main>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { api, clearToken, getToken, setToken } from './api/client'

const token = ref(getToken())
const tab = ref('dashboard')

const me = reactive({ username: '', nickname: '', role: '' })
const projects = ref([])
const activeProject = ref(null)
const commits = ref([])
const selectedCommit = ref(null)
const diffFiles = ref([])
const documents = ref([])
const tasks = ref([])
const reports = ref([])
const activeTask = ref(null)
const reportDetail = ref(null)
const mqLogs = ref([])
const aiLogs = ref([])
const aiLogScope = ref('项目维度')
const selectedAiLog = ref(null)
const selectedFile = ref(null)
const searchMatches = ref([])
const searched = ref(false)
const searchQuery = ref('发货前是否需要校验支付状态')
const docType = ref('BUSINESS_FLOW')
const deleteTarget = ref(null)

const openFeedback = reactive({})
const feedbackMap = reactive({})
const fbDraft = reactive({})

const busy = reactive({})
const toast = reactive({ text: '', type: '' })

const auth = reactive({ username: 'developer', password: '123456' })
const projectForm = reactive({ projectId: null, name: '', description: '', defaultBranch: 'main' })
const repoForm = reactive({ repoUrl: '', provider: 'GITHUB', defaultBranch: 'main', accessToken: '' })
const reviewForm = reactive({ commitId: '', baseCommitId: '', branch: '' })
const chosenDocs = ref(new Set())
const demoRepoPath = import.meta.env.VITE_DEMO_REPO_PATH || 'F:\\202605New\\demo-repos\\mall-order-service'

let pollTimer = null
const pollingActive = ref(false)

const tabTitles = { dashboard: '概览', projects: '项目管理', repository: '仓库配置', knowledge: 'RAG 知识库', reviews: '代码审查', aiLogs: 'AI 调用日志' }
const tabTitle = computed(() => tabTitles[tab.value] || 'RepoSage')
const repoBound = computed(() => commits.value.length > 0 || repoForm._bound)
const needsToken = computed(() => /^https?:\/\//i.test(repoForm.repoUrl.trim()))
const highRiskCount = computed(() => reports.value.filter(r => r.overallRisk === 'HIGH').length)

function toastMsg(text, type = '') {
  toast.text = text; toast.type = type
  setTimeout(() => (toast.text = ''), 3200)
}

async function run(action, key) {
  if (key) busy[key] = true
  try {
    await action()
  } catch (error) {
    const msg = error?.message || '操作失败'
    toastMsg(msg, 'error')
    if (msg.includes('未登录') || msg.includes('401')) logout()
  } finally {
    if (key) busy[key] = false
  }
}

/* ---------- auth ---------- */
async function login() {
  busy.auth = true
  try {
    const data = await api('/auth/login', { method: 'POST', body: JSON.stringify(auth) })
    setToken(data.token); token.value = data.token
    await afterLogin()
  } finally { busy.auth = false }
}
async function afterLogin() {
  await loadMe()
  await refreshAll()
  tab.value = 'dashboard'
  toastMsg('登录成功', 'success')
}
function logout() {
  clearToken(); token.value = ''; activeProject.value = null
  stopPolling()
}
async function loadMe() {
  try {
    const data = await api('/auth/me')
    Object.assign(me, data)
  } catch { /* token carries username if /me missing */ }
}

/* ---------- refresh ---------- */
async function refreshAll() {
  if (!token.value) return
  busy.refresh = true
  try {
    await loadProjects()
    if (activeProject.value) {
      await Promise.allSettled([loadDocuments(), loadReviews(), loadAiLogs()])
    }
  } finally { busy.refresh = false }
}
function goTab(t) { tab.value = t; if (activeProject.value) run(refreshAll) }

/* ---------- projects ---------- */
async function loadProjects() {
  busy.projects = true
  try {
    projects.value = await api('/projects')
    if (activeProject.value) {
      activeProject.value = projects.value.find(p => p.projectId === activeProject.value.projectId) || null
    }
    if (!activeProject.value && projects.value.length) activeProject.value = projects.value[0]
  } finally { busy.projects = false }
}
function resetProjectForm() { Object.assign(projectForm, { projectId: null, name: '', description: '', defaultBranch: 'main' }) }
function editProject(p) {
  Object.assign(projectForm, { projectId: p.projectId, name: p.name, description: p.description || '', defaultBranch: p.defaultBranch })
  toastMsg('正在编辑：' + p.name)
}
async function saveProject() {
  if (!projectForm.name.trim()) return toastMsg('请填写项目名称', 'error')
  const body = JSON.stringify({ name: projectForm.name, description: projectForm.description, defaultBranch: projectForm.defaultBranch })
  if (projectForm.projectId) {
    await api(`/projects/${projectForm.projectId}`, { method: 'PUT', body })
    toastMsg('项目已更新', 'success')
  } else {
    const created = await api('/projects', { method: 'POST', body })
    activeProject.value = created
    toastMsg('项目已创建', 'success')
  }
  resetProjectForm()
  await loadProjects()
}
function selectProject(p) {
  activeProject.value = p
  resetReviewState()
  tab.value = 'repository'
  run(refreshAll)
}
function askDeleteProject(p) { deleteTarget.value = p }
async function confirmDeleteProject() {
  busy.delete = true
  try {
    await api(`/projects/${deleteTarget.value.projectId}`, { method: 'DELETE' })
    if (activeProject.value && activeProject.value.projectId === deleteTarget.value.projectId) activeProject.value = null
    deleteTarget.value = null
    await loadProjects()
    toastMsg('项目已删除', 'success')
  } finally { busy.delete = false }
}

/* ---------- repository ---------- */
function useDemoRepository() {
  repoForm.repoUrl = demoRepoPath
  repoForm.provider = 'LOCAL'
  repoForm.defaultBranch = activeProject.value?.defaultBranch || 'main'
  repoForm.accessToken = ''
}
async function bindRepository() {
  busy.bind = true
  try {
    await api(`/projects/${activeProject.value.projectId}/repository`, { method: 'POST', body: JSON.stringify(repoForm) })
    repoForm._bound = true
    repoForm.accessToken = ''
    toastMsg('仓库已绑定', 'success')
    await loadCommits()
  } finally { busy.bind = false }
}
async function unbindRepository() {
  await api(`/projects/${activeProject.value.projectId}/repository`, { method: 'DELETE' })
  commits.value = []; selectedCommit.value = null; diffFiles.value = []; repoForm._bound = false
  toastMsg('已解绑仓库', 'success')
}
async function loadCommits() {
  busy.commits = true
  try {
    commits.value = await api(`/projects/${activeProject.value.projectId}/repository/commits?limit=100`)
    repoForm._bound = true
  } finally { busy.commits = false }
}
function selectCommit(c) {
  selectedCommit.value = c
  diffFiles.value = []
  reviewForm.commitId = c.commitId
  reviewForm.baseCommitId = c.parentCommitId || ''
  reviewForm.branch = activeProject.value?.defaultBranch || 'main'
}
async function loadDiff() {
  busy.diff = true
  try {
    const data = await api(`/projects/${activeProject.value.projectId}/repository/commits/${selectedCommit.value.commitId}/diff` +
      (reviewForm.baseCommitId ? `?baseCommitId=${encodeURIComponent(reviewForm.baseCommitId)}` : ''))
    diffFiles.value = data.files || []
  } finally { busy.diff = false }
}
function reviewSelectedCommit() {
  reviewForm.commitId = selectedCommit.value.commitId
  tab.value = 'reviews'
}

/* ---------- knowledge ---------- */
function onFileChange(e) { selectedFile.value = e.target.files?.[0] || null }
async function uploadDocument() {
  if (!selectedFile.value) return toastMsg('请选择文档', 'error')
  busy.upload = true
  try {
    const form = new FormData()
    form.append('file', selectedFile.value)
    await api(`/projects/${activeProject.value.projectId}/knowledge/documents?docType=${docType.value}`, { method: 'POST', body: form })
    selectedFile.value = null
    await loadDocuments()
    toastMsg('文档已入库', 'success')
  } finally { busy.upload = false }
}
async function loadDocuments() {
  documents.value = await api(`/projects/${activeProject.value.projectId}/knowledge/documents`)
  const ids = new Set(documents.value.map(d => d.documentId))
  chosenDocs.value = new Set([...chosenDocs.value].filter(id => ids.has(id)))
}
async function deleteDocument(id) {
  await api(`/projects/${activeProject.value.projectId}/knowledge/documents/${id}`, { method: 'DELETE' })
  await loadDocuments()
  toastMsg('文档已删除', 'success')
}
async function searchKnowledge() {
  busy.search = true; searched.value = true
  try {
    const data = await api(`/projects/${activeProject.value.projectId}/knowledge/search`, { method: 'POST', body: JSON.stringify({ query: searchQuery.value, topK: 5 }) })
    searchMatches.value = data.matches || []
  } finally { busy.search = false }
}

/* ---------- reviews ---------- */
function resetReviewState() {
  commits.value = []; selectedCommit.value = null; diffFiles.value = []
  tasks.value = []; reports.value = []; activeTask.value = null; reportDetail.value = null; mqLogs.value = []
  repoForm._bound = false
  chosenDocs.value = new Set()
}
async function createReview() {
  busy.review = true
  try {
    const commitId = reviewForm.commitId || selectedCommit.value?.commitId || ''
    const documentIds = Array.from(chosenDocs.value)
    await api(`/projects/${activeProject.value.projectId}/reviews/tasks`, { method: 'POST', body: JSON.stringify({ ...reviewForm, commitId, documentIds }) })
    await loadReviews()
    activeTask.value = tasks.value[0] || null
    if (reports.value[0]) await openReport(reports.value[0].reportId)
    toastMsg('审查任务已创建', 'success')
    maybeStartPolling()
  } finally { busy.review = false }
}
function toggleDoc(id) {
  const next = new Set(chosenDocs.value)
  next.has(id) ? next.delete(id) : next.add(id)
  chosenDocs.value = next
}
function selectAllDocs() { chosenDocs.value = new Set(documents.value.map(d => d.documentId)) }
function clearDocs() { chosenDocs.value = new Set() }
async function loadReviews() {
  busy.reviews = true
  try {
    tasks.value = await api(`/projects/${activeProject.value.projectId}/reviews/tasks`)
    reports.value = await api(`/projects/${activeProject.value.projectId}/reviews/reports`)
    if (activeTask.value) activeTask.value = tasks.value.find(t => t.taskId === activeTask.value.taskId) || activeTask.value
  } finally { busy.reviews = false }
}
function selectTask(t) { activeTask.value = t; mqLogs.value = [] }
async function openReport(reportId) {
  reportDetail.value = await api(`/projects/${activeProject.value.projectId}/reviews/reports/${reportId}`)
  tab.value = 'reviews'
}
async function loadMqLogs(taskId) { mqLogs.value = await api(`/mq/logs?taskId=${taskId}`) }

/* ---------- polling for running tasks ---------- */
function maybeStartPolling() {
  const running = tasks.value.some(t => t.status === 'PENDING' || t.status === 'RUNNING')
  if (running && !pollTimer) {
    pollingActive.value = true
    pollTimer = setInterval(async () => {
      try { await loadReviews() } catch { /* ignore */ }
      if (!tasks.value.some(t => t.status === 'PENDING' || t.status === 'RUNNING')) {
        stopPolling()
        if (reports.value[0]) await openReport(reports.value[0].reportId)
        toastMsg('审查已完成', 'success')
      }
    }, 2500)
  }
}
function stopPolling() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null } pollingActive.value = false }

/* ---------- feedback ---------- */
function ensureDraft(id) { if (!fbDraft[id]) fbDraft[id] = { type: 'TRUE_POSITIVE', comment: '' } ; return fbDraft[id] }
async function toggleFeedback(id) {
  openFeedback[id] = !openFeedback[id]
  if (openFeedback[id]) await loadFeedback(id)
}
async function loadFeedback(id) { feedbackMap[id] = await api(`/review-issues/${id}/feedback`) }
async function submitFeedback(issueId, type) {
  await api(`/review-issues/${issueId}/feedback`, { method: 'POST', body: JSON.stringify({ feedbackType: type, comment: '' }) })
  openFeedback[issueId] = true
  await loadFeedback(issueId)
  toastMsg('反馈已提交', 'success')
}
async function submitFeedbackForm(issueId) {
  const d = ensureDraft(issueId)
  await api(`/review-issues/${issueId}/feedback`, { method: 'POST', body: JSON.stringify({ feedbackType: d.type, comment: d.comment }) })
  d.comment = ''
  await loadFeedback(issueId)
  toastMsg('反馈已提交', 'success')
}

/* ---------- ai logs ---------- */
async function loadAiLogs(taskId = null) {
  if (!activeProject.value) return
  const query = taskId ? `taskId=${taskId}&limit=50` : `projectId=${activeProject.value.projectId}&limit=50`
  aiLogs.value = await api(`/ai/logs?${query}`)
  aiLogScope.value = taskId ? `任务 #${taskId} 维度` : '项目维度'
}
async function openProjectAiLogs() { if (!activeProject.value) return; await loadAiLogs(); tab.value = 'aiLogs' }
async function openTaskAiLogs(taskId) { await loadAiLogs(taskId); tab.value = 'aiLogs' }

/* ---------- helpers ---------- */
function shortCommit(id) { return id ? id.slice(0, 8) : '-' }
function fmtTime(t) { if (!t) return '-'; const d = new Date(t); return `${d.getMonth()+1}/${d.getDate()} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}` }
function fmtDate(t) { if (!t) return '-'; const d = new Date(t); return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}` }
function mqStatusClass(s) { return s === 'CONSUMED' || s === 'PUBLISHED' ? 'SUCCESS' : (s === 'DEAD' ? 'DEAD' : 'FAILED') }
function fbLabel(t) { return { TRUE_POSITIVE: '真实问题', FALSE_POSITIVE: '误报', NEED_DISCUSSION: '需讨论' }[t] || t }
function fbBadge(t) { return { TRUE_POSITIVE: 'risk-LOW', FALSE_POSITIVE: 'risk-HIGH', NEED_DISCUSSION: 'risk-MEDIUM' }[t] || 'risk-NONE' }
function diffLines(diff) {
  if (!diff) return []
  return diff.split(/\r?\n/).map(text => {
    let cls = ''
    if (text.startsWith('@@')) cls = 'hunk'
    else if (text.startsWith('+++') || text.startsWith('---') || text.startsWith('diff ') || text.startsWith('index ')) cls = 'meta'
    else if (text.startsWith('+')) cls = 'add'
    else if (text.startsWith('-')) cls = 'del'
    return { text: text || ' ', cls }
  })
}

onMounted(async () => {
  if (token.value) { await loadMe(); await refreshAll() }
})
onUnmounted(stopPolling)
</script>
