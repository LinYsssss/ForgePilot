<template>
  <!-- ===================== AUTH ===================== -->
  <div v-if="!authenticated" class="auth-wrap">
    <div class="auth-card">
      <div class="brand">
        <div class="brand-logo">R</div>
        <div><h1>RepoSage</h1></div>
      </div>
      <p class="auth-sub">AI 代码仓库智能审查平台</p>
      <div class="grid">
        <label class="field">用户名
          <input v-model="auth.username" placeholder="ysainlin" autocomplete="username" @keyup.enter="login" />
        </label>
        <label class="field">密码
          <input v-model="auth.password" type="password" placeholder="至少 6 位" autocomplete="current-password" @keyup.enter="login" />
        </label>
      </div>
      <div class="actions">
        <button @click="login" :disabled="busy.auth">
          <span v-if="busy.auth" class="spinner"></span>登录
        </button>
      </div>
      <p class="hint">账号由管理员分配，如需账号请联系管理员。</p>
    </div>
    <transition name="t"><div v-if="toast.text" class="toast" :class="toast.type" role="status">{{ toast.text }}</div></transition>
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

      <nav aria-label="主导航">
        <button :class="{ active: tab === 'dashboard' }" @click="tab = 'dashboard'">
          <span class="nav-ico" aria-hidden="true">▦</span> 概览
        </button>
        <button :class="{ active: tab === 'projects' }" @click="tab = 'projects'">
          <span class="nav-ico" aria-hidden="true">▤</span> 项目
        </button>
        <button :class="{ active: tab === 'repository' }" @click="goTab('repository')" :disabled="!activeProject">
          <span class="nav-ico" aria-hidden="true">⎇</span> 仓库
        </button>
        <button :class="{ active: tab === 'pullRequests' }" @click="goTab('pullRequests')" :disabled="!activeProject">
          <span class="nav-ico" aria-hidden="true">⑂</span> PR 工作流
        </button>
        <button :class="{ active: tab === 'knowledge' }" @click="goTab('knowledge')" :disabled="!activeProject">
          <span class="nav-ico" aria-hidden="true">▣</span> 知识库
        </button>
        <button :class="{ active: tab === 'reviews' }" @click="goTab('reviews')" :disabled="!activeProject">
          <span class="nav-ico" aria-hidden="true">✓</span> 审查
        </button>
        <button :class="{ active: tab === 'agent' }" @click="openAgentWorkspace" :disabled="!activeProject">
          <span class="nav-ico" aria-hidden="true">◆</span> Agent 审批
        </button>
        <button :class="{ active: tab === 'aiLogs' }" @click="openProjectAiLogs" :disabled="!activeProject">
          <span class="nav-ico" aria-hidden="true">◷</span> AI 日志
        </button>
      </nav>

      <div class="sidebar-foot">
        <div class="user-chip">
          <div class="avatar">{{ (me.nickname || me.username || 'U').slice(0,1).toUpperCase() }}</div>
          <div>
            <div class="uname">{{ me.nickname || me.username }}</div>
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
          <span v-if="activeProject" class="muted">/ {{ activeProject.name }} · 默认分支 <span class="mono">{{ activeProject.defaultBranch }}</span></span>
        </div>
        <div class="topbar-actions">
          <button class="theme-toggle" :title="theme === 'dark' ? '切换到浅色模式' : '切换到深色模式'" :aria-label="theme === 'dark' ? '切换到浅色模式' : '切换到深色模式'" @click="toggleTheme">
            {{ theme === 'dark' ? '☀' : '☾' }}
          </button>
          <button class="secondary" @click="run(refreshAll)" :disabled="busy.refresh">
            <span v-if="busy.refresh" class="spinner dark"></span>刷新
          </button>
        </div>
      </header>

      <!-- ============ DASHBOARD ============ -->
      <template v-if="tab === 'dashboard'">
        <div class="stat-grid">
          <div class="stat"><span class="spark" aria-hidden="true">▤</span><div class="label">项目总数</div><div class="value">{{ projects.length }}</div></div>
          <div class="stat"><span class="spark" aria-hidden="true">✓</span><div class="label">审查任务</div><div class="value">{{ tasks.length }}</div></div>
          <div class="stat"><span class="spark" aria-hidden="true">▦</span><div class="label">审查报告</div><div class="value">{{ reports.length }}</div></div>
          <div class="stat">
            <span class="spark" aria-hidden="true">⚠</span>
            <div class="label">高风险报告</div>
            <div class="value" :class="highRiskCount ? 'tinted-high' : 'tinted-ok'">{{ highRiskCount }}</div>
          </div>
        </div>

        <DashboardViz v-if="activeProject" :reports="reports" />

        <div class="panel">
          <div class="panel-head">
            <div><h2>最近审查报告</h2><div class="sub">{{ activeProject ? activeProject.name : '请选择项目' }}</div></div>
            <button class="sm secondary" @click="goTab('reviews')" :disabled="!activeProject">前往审查 →</button>
          </div>
          <div v-if="!activeProject" class="empty"><div class="ico" aria-hidden="true">▤</div><p>还没有选择项目</p><p>去“项目”页创建或选择一个项目。</p></div>
          <div v-else-if="!reports.length" class="empty"><div class="ico" aria-hidden="true">✓</div><p>暂无审查报告</p><p>绑定仓库并触发一次代码审查吧。</p></div>
          <div v-else class="list">
            <button v-for="r in reports.slice(0,6)" :key="r.reportId" class="list-row row-reports" @click="openReport(r.reportId)">
              <span class="mono">#{{ r.reportId }}</span>
              <span class="grow"><span class="mono">{{ shortCommit(r.commitId) }}</span> · {{ fmtTime(r.createdAt) }}</span>
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
          <div v-if="busy.projects" class="skeleton"><div class="sk-row" v-for="n in 3" :key="n"></div></div>
          <div v-else-if="!projects.length" class="empty"><div class="ico" aria-hidden="true">▤</div><p>还没有项目</p><p>在上方创建你的第一个项目。</p></div>
          <div v-else class="proj-grid">
            <div v-for="p in projects" :key="p.projectId" class="proj-card" :class="{ active: activeProject && activeProject.projectId === p.projectId }" tabindex="0" @click="selectProject(p)" @keyup.enter="selectProject(p)">
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
                <span class="mono">⎇ {{ p.defaultBranch }}</span>
                <span class="when">{{ fmtDate(p.createdAt) }}</span>
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
                <pre class="diff-body"><code><span v-for="(ln, i) in diffLines(f.diff)" :key="i" class="diff-line" :class="ln.cls">{{ ln.text }}</span></code></pre>
              </div>
            </div>
          </template>
        </div>
      </template>

      <!-- ============ PULL REQUESTS ============ -->
      <template v-else-if="tab === 'pullRequests'">
        <div class="panel">
          <div class="panel-head">
            <div><h2>{{ pullRequestForm.pullRequestId ? '更新 PR' : '登记 PR' }}</h2><div class="sub">把真实团队里的 Pull Request 纳入审查闭环</div></div>
            <div class="head-actions">
              <button v-if="pullRequestForm.pullRequestId" class="sm secondary" @click="resetPullRequestForm">取消编辑</button>
              <button class="sm secondary" @click="fillPrFromSelectedCommit" :disabled="!selectedCommit">使用已选 Commit</button>
            </div>
          </div>
          <div class="grid four">
            <label class="field">PR 编号<input v-model.number="pullRequestForm.prNumber" type="number" min="1" placeholder="123" /></label>
            <label class="field">Provider
              <select v-model="pullRequestForm.provider">
                <option value="GITHUB">GitHub</option>
                <option value="GITLAB">GitLab</option>
                <option value="GITEE">Gitee</option>
                <option value="OTHER">其他</option>
              </select>
            </label>
            <label class="field" style="grid-column: span 2">标题<input v-model="pullRequestForm.title" placeholder="feat: add order review gate" /></label>
          </div>
          <div class="grid four" style="margin-top:16px">
            <label class="field">作者<input v-model="pullRequestForm.authorName" placeholder="developer" /></label>
            <label class="field">源分支<input v-model="pullRequestForm.sourceBranch" placeholder="feature/order-gate" /></label>
            <label class="field">目标分支<input v-model="pullRequestForm.targetBranch" :placeholder="activeProject ? activeProject.defaultBranch : 'main'" /></label>
            <label class="field">外部 PR ID<input v-model="pullRequestForm.externalPrId" placeholder="可选" /></label>
          </div>
          <div class="grid two" style="margin-top:16px">
            <label class="field">Base SHA / Ref<input v-model="pullRequestForm.baseSha" placeholder="main 或 base commit" /></label>
            <label class="field">Head SHA / Ref<input v-model="pullRequestForm.headSha" placeholder="feature 分支或 head commit" /></label>
          </div>
          <div class="actions">
            <button @click="run(savePullRequest)" :disabled="busy.pullRequest">
              <span v-if="busy.pullRequest" class="spinner"></span>{{ pullRequestForm.pullRequestId ? '保存 PR 更新' : '登记 PR' }}
            </button>
            <button class="secondary" @click="run(loadPullRequests)" :disabled="busy.pullRequests">刷新 PR</button>
          </div>
        </div>

        <div class="split">
          <div class="panel">
            <div class="panel-head"><div><h2>PR 列表</h2><div class="sub">{{ pullRequests.length }} 条</div></div></div>
            <div v-if="!pullRequests.length" class="empty"><div class="ico" aria-hidden="true">⑂</div><p>暂无 PR</p><p>先登记一个 PR，再触发审查。</p></div>
            <div v-else class="list">
              <div v-for="pr in pullRequests" :key="pr.pullRequestId" class="list-row row-prs" :class="{ selected: activePullRequest && activePullRequest.pullRequestId === pr.pullRequestId }" @click="selectPullRequest(pr)">
                <span class="mono">#{{ pr.prNumber || pr.pullRequestId }}</span>
                <span class="grow">{{ pr.title }}</span>
                <span class="status-pill" :class="'st-' + pr.reviewState">{{ prStateLabel(pr.reviewState) }}</span>
                <span class="mono">{{ shortCommit(pr.headSha) }}</span>
              </div>
            </div>
          </div>

          <div class="panel">
            <div class="panel-head">
              <div><h2>PR 审查闭环</h2><div class="sub">{{ activePullRequest ? activePullRequest.title : '请选择 PR' }}</div></div>
              <button v-if="activePullRequest" class="sm secondary" @click="editPullRequest(activePullRequest)">编辑 PR</button>
            </div>
            <div v-if="!activePullRequest" class="empty"><div class="ico" aria-hidden="true">⑂</div><p>选择一个 PR</p></div>
            <template v-else>
              <div class="pr-meta">
                <span class="badge plain">{{ activePullRequest.provider }}</span>
                <span class="status-pill" :class="'st-' + activePullRequest.status">{{ activePullRequest.status }}</span>
                <span class="mono">{{ activePullRequest.sourceBranch }} → {{ activePullRequest.targetBranch }}</span>
              </div>

              <div class="kb-select compact">
                <div class="kb-head">
                  <span class="section-title" style="margin:0">参与 PR 审查的知识库</span>
                  <div class="kb-tools">
                    <button class="sm secondary" @click="selectAllDocs" :disabled="!documents.length">全选</button>
                    <button class="sm secondary" @click="clearDocs" :disabled="!documents.length">清空</button>
                  </div>
                </div>
                <div v-if="!documents.length" class="hint">该项目暂无知识库文档；不选则审查仅基于 PR diff。</div>
                <div v-else class="kb-chips">
                  <label v-for="d in documents" :key="d.documentId" class="kb-chip" :class="{ on: chosenDocs.has(d.documentId) }">
                    <input type="checkbox" :checked="chosenDocs.has(d.documentId)" @change="toggleDoc(d.documentId)" />
                    <span class="kb-name">{{ d.fileName }}</span>
                  </label>
                </div>
              </div>

              <div class="actions">
                <button @click="run(createPrReview)" :disabled="busy.prReview">
                  <span v-if="busy.prReview" class="spinner"></span>触发 PR 审查
                </button>
                <button class="secondary" @click="run(loadReviews)" :disabled="busy.reviews">刷新报告</button>
              </div>

              <div class="section-title">PR 审查报告</div>
              <div v-if="!prReports.length" class="empty"><div class="ico" aria-hidden="true">▦</div><p>这个 PR 暂无报告</p></div>
              <div v-else class="list pr-report-list">
                <div v-for="r in prReports" :key="r.reportId" class="list-row row-reports" :class="{ selected: prActionForm.reportId === r.reportId }" @click="selectPrReport(r.reportId)">
                  <span class="mono">#{{ r.reportId }}</span>
                  <span class="grow">{{ r.issueCount }} 个问题</span>
                  <span class="badge" :class="'risk-' + r.overallRisk">{{ r.overallRisk }}</span>
                  <button class="sm secondary" @click.stop="openReport(r.reportId)">查看</button>
                </div>
              </div>

              <div class="section-title">管理员动作</div>
              <div class="grid two">
                <label class="field">动作
                  <select v-model="prActionForm.actionType">
                    <option value="REQUEST_CHANGES">打回修改</option>
                    <option value="APPROVE">通过</option>
                    <option value="WAIVE">风险豁免</option>
                    <option value="COMMENT">仅评论</option>
                  </select>
                </label>
                <label class="field">关联报告
                  <select v-model.number="prActionForm.reportId" @change="loadActionReport">
                    <option :value="null">不关联</option>
                    <option v-for="r in prReports" :key="r.reportId" :value="r.reportId">#{{ r.reportId }} · {{ r.overallRisk }} · {{ r.issueCount }} 问题</option>
                  </select>
                </label>
              </div>
              <div v-if="actionReportDetail && actionReportDetail.issues.length" class="issue-picker">
                <div class="kb-head">
                  <span class="section-title" style="margin:0">关联问题项</span>
                  <div class="kb-tools">
                    <button class="sm secondary" @click="selectBlockingIssues">选择高/中危</button>
                    <button class="sm secondary" @click="clearActionIssues">清空</button>
                  </div>
                </div>
                <label v-for="issue in actionReportDetail.issues" :key="issue.issueId" class="issue-check" :class="{ on: actionIssueIds.has(issue.issueId) }">
                  <input type="checkbox" :checked="actionIssueIds.has(issue.issueId)" @change="toggleActionIssue(issue.issueId)" />
                  <span class="badge" :class="'sev-' + issue.severity">{{ issue.severity }}</span>
                  <span class="grow">{{ issue.title }}</span>
                </label>
              </div>
              <label class="field" style="margin-top:16px">原因<textarea v-model="prActionForm.reason" placeholder="例如：存在高风险权限问题，暂不允许合并"></textarea></label>
              <label class="field" style="margin-top:12px">整改要求<textarea v-model="prActionForm.requirement" placeholder="例如：补充权限校验，并新增对应测试"></textarea></label>
              <div class="actions">
                <button @click="run(submitPrAction)" :disabled="busy.prAction">
                  <span v-if="busy.prAction" class="spinner"></span>提交动作
                </button>
              </div>

              <div v-if="prActions.length" class="list action-history">
                <div v-for="a in prActions" :key="a.actionId" class="list-row action-row">
                  <span class="status-pill" :class="'st-' + actionStateClass(a.actionType)">{{ actionLabel(a.actionType) }}</span>
                  <span class="grow">{{ a.reason || a.requirement || '无补充说明' }}</span>
                  <span class="mono">{{ fmtTime(a.createdAt) }}</span>
                </div>
              </div>
            </template>
          </div>
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
                <span class="badge plain">{{ d.docType }}</span>
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
            <div v-if="!tasks.length" class="empty"><div class="ico" aria-hidden="true">✓</div><p>暂无任务</p></div>
            <div v-else class="list">
              <div v-for="t in tasks" :key="t.taskId" class="list-row row-tasks" :class="{ selected: activeTask && activeTask.taskId === t.taskId }" @click="selectTask(t)">
                <span class="mono">#{{ t.taskId }}</span>
                <span class="grow mono">{{ shortCommit(t.commitId) }}</span>
                <span class="status-pill" :class="'st-' + t.status">{{ statusLabel(t.status) }}</span>
                <span class="row-actions">
                  <button v-if="t.status === 'PENDING' || t.status === 'RUNNING'" class="warn sm" title="停止任务" @click.stop="run(() => cancelTask(t))">停止</button>
                  <button class="danger sm" title="删除任务" @click.stop="askDeleteTask(t)">删除</button>
                </span>
              </div>
            </div>
          </div>
          <div class="panel">
            <div class="panel-head"><div><h2>审查报告</h2><div class="sub">{{ reports.length }} 条</div></div></div>
            <div v-if="!reports.length" class="empty"><div class="ico" aria-hidden="true">▦</div><p>暂无报告</p></div>
            <div v-else class="list">
              <div v-for="r in reports" :key="r.reportId" class="list-row row-reports" :class="{ selected: reportDetail && reportDetail.reportId === r.reportId }" @click="openReport(r.reportId)">
                <span class="mono">#{{ r.reportId }}</span>
                <span class="grow">{{ r.issueCount }} 个问题</span>
                <span class="badge" :class="'risk-' + r.overallRisk">{{ r.overallRisk }}</span>
                <span class="row-actions">
                  <button class="danger sm" title="删除报告" @click.stop="askDeleteReport(r)">删除</button>
                </span>
              </div>
            </div>
          </div>
        </div>

        <div class="panel" v-if="activeTask">
          <div class="panel-head">
            <div><h2>任务 #{{ activeTask.taskId }} 详情</h2><div class="sub">{{ statusLabel(activeTask.status) }} · <span class="mono">{{ shortCommit(activeTask.commitId) }}</span></div></div>
            <div class="head-actions">
              <button v-if="activeTask.status === 'PENDING' || activeTask.status === 'RUNNING'" class="sm warn" @click="run(() => cancelTask(activeTask))">停止任务</button>
              <button class="sm secondary" @click="run(() => loadMqLogs(activeTask.taskId))">MQ 日志</button>
              <button class="sm secondary" @click="run(() => openTaskAiLogs(activeTask.taskId))">AI 日志</button>
            </div>
          </div>
          <p v-if="activeTask.errorMessage" class="badge plain risk-HIGH" style="display:block;padding:10px">错误：{{ activeTask.errorMessage }}</p>
          <div v-if="mqLogs.length" class="list">
            <div v-for="(l, i) in mqLogs" :key="i" class="list-row" style="grid-template-columns: 110px 1fr auto; cursor:default">
              <span class="status-pill" :class="'st-' + mqStatusClass(l.status)">{{ l.status }}</span>
              <span class="grow mono">{{ l.routingKey }}</span>
              <span class="mono">重试 {{ l.retryCount }}</span>
            </div>
          </div>
        </div>

        <div class="panel" v-if="reportDetail">
          <div class="panel-head">
            <div><h2>审查报告 #{{ reportDetail.reportId }}</h2><div class="sub"><span class="mono">{{ shortCommit(reportDetail.commitId) }}</span> · {{ fmtTime(reportDetail.createdAt) }}</div></div>
            <button class="sm danger" @click="askDeleteReport(reportDetail)">删除报告</button>
          </div>

          <div class="report-summary">
            <div class="risk-dial" :class="'r-' + reportDetail.overallRisk">{{ reportDetail.overallRisk }}</div>
            <div class="rs-body">
              <h3>{{ reportDetail.summary || '审查完成' }}</h3>
              <div class="rs-meta">
                <span class="sev-tally risk-NONE">共 {{ reportDetail.issues.length }} 问题</span>
                <span v-if="severityTally.HIGH" class="sev-tally risk-HIGH">{{ severityTally.HIGH }} 高危</span>
                <span v-if="severityTally.MEDIUM" class="sev-tally risk-MEDIUM">{{ severityTally.MEDIUM }} 中危</span>
                <span v-if="severityTally.LOW" class="sev-tally risk-LOW">{{ severityTally.LOW }} 低危</span>
              </div>
              <div class="sev-strip" v-if="severityStrip.length" role="img" aria-label="严重度分布">
                <span v-for="seg in severityStrip" :key="seg.key" class="sev-seg" :style="{ width: seg.pct + '%', background: seg.color }" :title="`${seg.label}危 ${seg.count}`"></span>
              </div>
            </div>
          </div>

          <div v-if="!reportDetail.issues.length" class="empty"><div class="ico" aria-hidden="true">✓</div><p>未发现明显风险</p></div>
          <div v-for="issue in sortedIssues" :key="issue.issueId" class="issue" :class="'sevbar-' + issue.severity">
            <div class="issue-head">
              <span class="badge" :class="'sev-' + issue.severity">{{ issue.severity }}</span>
              <span class="badge plain">{{ issue.category }}</span>
              <h4>{{ issue.title }}</h4>
              <span v-if="issue.filePath" class="file">{{ issue.filePath }}<template v-if="issue.lineStart">:{{ issue.lineStart }}</template></span>
            </div>
            <p>{{ issue.description }}</p>
            <div class="kv" v-if="issue.impact"><b>影响</b><span>{{ issue.impact }}</span></div>
            <div class="callout co-evidence" v-if="issue.evidence"><span class="co-tag">证据</span><span class="co-body">{{ issue.evidence }}</span></div>
            <div class="callout co-fix" v-if="issue.suggestion"><span class="co-tag">建议</span><span class="co-body">{{ issue.suggestion }}</span></div>
            <div class="conf" v-if="issue.confidence != null" :class="confClass(issue.confidence)">
              <span class="conf-label">置信度</span>
              <span class="conf-bar"><span :style="{ width: Math.round(issue.confidence*100) + '%' }"></span></span>
              <span class="conf-pct">{{ Math.round(issue.confidence*100) }}%</span>
              <span class="conf-tag">{{ confText(issue.confidence) }}</span>
            </div>

            <div class="issue-foot">
              <button class="vote sm" :class="voteClass(issue.issueId, 'TRUE_POSITIVE')" @click="run(() => vote(issue.issueId, 'TRUE_POSITIVE'))">👍 真实问题</button>
              <button class="vote sm" :class="voteClass(issue.issueId, 'FALSE_POSITIVE')" @click="run(() => vote(issue.issueId, 'FALSE_POSITIVE'))">🚫 误报</button>
              <button class="vote sm" :class="voteClass(issue.issueId, 'NEED_DISCUSSION')" @click="run(() => vote(issue.issueId, 'NEED_DISCUSSION'))">💬 需讨论</button>
              <button class="sm secondary" @click="toggleFeedback(issue.issueId)">{{ openFeedback[issue.issueId] ? '收起反馈' : `查看反馈${feedbackCount(issue.issueId)}` }}</button>
              <button v-if="myVote(issue.issueId)" class="sm danger" @click="run(() => removeMyFeedback(issue.issueId))">撤回我的反馈</button>
            </div>

            <div v-if="openFeedback[issue.issueId]">
              <div class="fb-row">
                <input :value="ensureDraft(issue.issueId).comment" @input="e => ensureDraft(issue.issueId).comment = e.target.value" placeholder="给当前投票补充说明（可选）" @keyup.enter="run(() => submitFeedbackForm(issue.issueId))" />
                <button class="sm" @click="run(() => submitFeedbackForm(issue.issueId))" :disabled="!myVote(issue.issueId)">保存说明</button>
              </div>
              <p v-if="!myVote(issue.issueId)" class="fb-empty">先在上方选择一个投票（真实问题 / 误报 / 需讨论），再补充说明。</p>
              <div class="fb-list" v-if="(feedbackMap[issue.issueId] || []).length">
                <div v-for="fb in feedbackMap[issue.issueId]" :key="fb.feedbackId" class="fb-item" :class="{ mine: fb.mine }">
                  <span class="badge plain" :class="fbBadge(fb.feedbackType)">{{ fbLabel(fb.feedbackType) }}</span>
                  <span class="who">{{ fb.username }}</span>
                  <span v-if="fb.mine" class="you-tag">你</span>
                  <span class="grow">{{ fb.comment || '—' }}</span>
                  <span class="when">{{ fmtTime(fb.updatedAt || fb.createdAt) }}</span>
                </div>
              </div>
              <p v-else class="fb-empty">还没有反馈记录。</p>
            </div>
          </div>
        </div>
      </template>

      <!-- ============ AGENT PATCH APPROVAL ============ -->
      <template v-else-if="tab === 'agent'">
        <div class="panel">
          <div class="panel-head"><div><h2>Agent 审查与 Patch 审批</h2><div class="sub">查看 Timeline、Finding 证据、验证日志与候选 Patch</div></div></div>
          <div v-if="agentRunDetail" class="agent-live-status">
            <span class="status-pill" :class="'st-' + agentRunDetail.status" :title="agentRunDetail.status">{{ statusLabel(agentRunDetail.status) }}</span>
            <span>{{ agentPolling ? '正在自动刷新持久化状态' : (agentRunDetail.terminal ? '运行已结束' : '自动刷新已暂停') }}</span>
            <button v-if="!agentRunDetail.terminal" class="sm danger" :disabled="busy.agentControl" @click="askCancelAgentRun">取消运行</button>
            <button v-if="['FAILED','TIMED_OUT'].includes(agentRunDetail.status)" class="sm secondary" :disabled="busy.agentControl" @click="askRetryAgentRun">重试失败步骤</button>
          </div>
          <div class="grid three">
            <label class="field">Run 状态筛选
              <select v-model="agentRunFilter">
                <option value="ALL">全部（{{ agentRuns.length }}）</option><option value="ACTIVE">运行中（{{ agentRunCounts.active }}）</option><option value="WAITING">等待审批（{{ agentRunCounts.waiting }}）</option><option value="FAILED">失败（{{ agentRunCounts.failed }}）</option><option value="DONE">已完成（{{ agentRunCounts.done }}）</option>
              </select>
            </label>
            <label class="field">最近 Agent Run
              <select v-model.number="agentRunId" @change="selectAgentRun">
                <option :value="null">请选择</option>
                <option v-for="runItem in filteredAgentRuns" :key="runItem.id" :value="runItem.id">#{{ runItem.id }} · {{ statusLabel(runItem.status) }} · {{ shortCommit(runItem.headSha) }}</option>
              </select>
              <small v-if="!filteredAgentRuns.length" class="field-hint">当前筛选没有匹配项，<button type="button" class="inline-link" @click="agentRunFilter = 'ALL'">显示全部</button></small>
            </label>
            <label class="field">当前 Head SHA<input v-model="agentHeadSha" /></label>
            <div class="actions"><button :disabled="busy.agentRuns" class="secondary" @click="run(loadAgentRuns)">刷新列表</button><button :disabled="!agentRunId || busy.agent" @click="run(loadAgentWorkspace)">加载</button></div>
          </div>
        </div>
        <AgentReviewWorkspace v-if="agentRunId && agentPatch" :project-id="activeProject.projectId"
          :agent-run-id="agentRunId" :current-head-sha="agentHeadSha" :timeline="agentTimeline"
          :findings="agentFindings" :patch="agentPatch" @decided="onPatchDecided" @error="onPatchError" />
        <div v-else class="empty"><p>输入 Agent Run ID 与当前 Head SHA 后加载候选 Patch。</p></div>
      </template>

      <!-- ============ AI LOGS ============ -->
      <template v-else-if="tab === 'aiLogs'">
        <div class="panel">
          <div class="panel-head">
            <div><h2>AI 调用日志</h2><div class="sub">{{ aiLogScope }} · 共 {{ aiLogs.length }} 条，按日期归类</div></div>
            <button class="sm secondary" @click="run(openProjectAiLogs)">刷新项目日志</button>
          </div>
          <div v-if="!aiLogs.length" class="empty"><div class="ico" aria-hidden="true">◷</div><p>暂无调用日志</p><p>执行一次审查或检索后会生成。</p></div>
          <div v-else>
            <div v-for="g in groupedAiLogs" :key="g.date" class="log-group">
              <button class="log-group-head" :class="{ collapsed: collapsedDates[g.date] }" @click="toggleDate(g.date)">
                <span class="caret" aria-hidden="true">▾</span>
                <span class="date">{{ g.date }} <span class="rel">{{ g.relative }}</span></span>
                <span class="divider"></span>
                <span class="count">{{ g.items.length }} 次调用</span>
              </button>
              <div v-show="!collapsedDates[g.date]" class="log-group-body">
                <div v-for="tg in g.taskGroups" :key="tg.key" class="log-task-group">
                  <div class="log-task-label">
                    <span v-if="tg.taskId">🧪 任务 <span class="mono">#{{ tg.taskId }}</span></span>
                    <span v-else>🔎 项目级调用（检索 / 向量）</span>
                    <span class="grow"></span>
                    <span class="count">{{ tg.items.length }}</span>
                  </div>
                  <div class="list">
                    <button v-for="l in tg.items" :key="l.id" class="list-row row-ailog" :class="{ selected: selectedAiLog && selectedAiLog.id === l.id }" @click="selectedAiLog = l">
                      <span class="badge plain">{{ l.requestType }}</span>
                      <span class="grow mono hide-sm">{{ l.provider }} / {{ l.model }}</span>
                      <span class="status-pill" :class="'st-' + l.status">{{ l.status }}</span>
                      <span class="mono hide-sm">{{ l.latencyMs }}ms</span>
                      <span class="mono hide-sm">{{ l.promptChars }}→{{ l.responseChars }}</span>
                      <span v-if="l.totalTokens" class="mono hide-sm">{{ l.totalTokens }} tok</span>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div class="panel" v-if="selectedAiLog">
          <div class="panel-head"><div><h2>调用详情 #{{ selectedAiLog.id }}</h2><div class="sub">{{ fmtTime(selectedAiLog.createdAt) }}</div></div><button class="sm secondary" @click="selectedAiLog = null">收起</button></div>
          <div class="grid two">
            <div class="kv"><b>类型</b><span>{{ selectedAiLog.requestType }}</span></div>
            <div class="kv"><b>Provider</b><span>{{ selectedAiLog.provider }}</span></div>
            <div class="kv"><b>模型</b><span>{{ selectedAiLog.model }}</span></div>
            <div class="kv"><b>耗时</b><span>{{ selectedAiLog.latencyMs }} ms</span></div>
            <div class="kv"><b>输入</b><span>{{ selectedAiLog.promptChars }} 字符</span></div>
            <div class="kv"><b>输出</b><span>{{ selectedAiLog.responseChars }} 字符</span></div>
            <div class="kv"><b>Token</b><span>{{ selectedAiLog.promptTokens }} 入 / {{ selectedAiLog.completionTokens }} 出 / {{ selectedAiLog.totalTokens }} 总</span></div>
          </div>
          <p v-if="selectedAiLog.errorMessage" class="badge plain risk-HIGH" style="display:block;padding:10px;margin-top:10px">{{ selectedAiLog.errorMessage }}</p>
        </div>
      </template>
    </section>

    <!-- confirm modal -->
    <transition name="t">
      <div v-if="confirmModal" class="modal-backdrop" @click.self="confirmModal = null" @keyup.esc="confirmModal = null">
        <div class="modal" role="dialog" aria-modal="true">
          <h3>{{ confirmModal.title }}</h3>
          <p>{{ confirmModal.body }}</p>
          <div class="actions">
            <button class="secondary" @click="confirmModal = null">取消</button>
            <button class="danger solid" @click="run(confirmAction)" :disabled="busy.confirm">
              <span v-if="busy.confirm" class="spinner"></span>{{ confirmModal.confirmLabel || '确认删除' }}
            </button>
          </div>
        </div>
      </div>
    </transition>

    <transition name="t"><div v-if="toast.text" class="toast" :class="toast.type" role="status">{{ toast.text }}</div></transition>
  </main>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { fmtDate, fmtTime, shortCommit } from './utils/format'
import { useTheme } from './composables/useTheme'
import { useToast } from './composables/useToast'
import { useSession } from './composables/useSession'
import { api } from './api/client'
import AgentReviewWorkspace from './components/agent/AgentReviewWorkspace.vue'
import DashboardViz from './components/DashboardViz.vue'

const { authenticated, me, projects, activeProject } = useSession()
const tab = ref('dashboard')

const { theme, toggleTheme } = useTheme()

const commits = ref([])
const selectedCommit = ref(null)
const diffFiles = ref([])
const documents = ref([])
const tasks = ref([])
const reports = ref([])
const pullRequests = ref([])
const activePullRequest = ref(null)
const prActions = ref([])
const actionReportDetail = ref(null)
const actionIssueIds = ref(new Set())
const activeTask = ref(null)
const reportDetail = ref(null)
const mqLogs = ref([])
const aiLogs = ref([])
const aiLogScope = ref('项目维度')
const selectedAiLog = ref(null)
const collapsedDates = reactive({})
const selectedFile = ref(null)
const searchMatches = ref([])
const searched = ref(false)
const searchQuery = ref('发货前是否需要校验支付状态')
const docType = ref('BUSINESS_FLOW')
const confirmModal = ref(null)
const agentRunId = ref(null)
const agentRuns = ref([])
const agentRunFilter = ref('ALL')
const agentHeadSha = ref('')
const agentTimeline = ref([])
const agentFindings = ref([])
const agentPatch = ref(null)
const agentRunDetail = ref(null)
const agentPolling = ref(false)

const openFeedback = reactive({})
const feedbackMap = reactive({})
const fbDraft = reactive({})

const busy = reactive({})
const { toast, toastMsg } = useToast()

const auth = reactive({ username: 'ysainlin', password: '' })
const projectForm = reactive({ projectId: null, name: '', description: '', defaultBranch: 'main' })
const repoForm = reactive({ repoUrl: '', provider: 'GITHUB', defaultBranch: 'main', accessToken: '' })
const reviewForm = reactive({ commitId: '', baseCommitId: '', branch: '' })
const pullRequestForm = reactive({ pullRequestId: null, prNumber: null, title: '', authorName: '', sourceBranch: '', targetBranch: 'main', baseSha: '', headSha: '', provider: 'GITHUB', externalPrId: '', status: 'OPEN' })
const prActionForm = reactive({ actionType: 'REQUEST_CHANGES', reportId: null, reason: '', requirement: '' })
const chosenDocs = ref(new Set())
const demoRepoPath = import.meta.env.VITE_DEMO_REPO_PATH || 'F:\\202605New\\demo-repos\\mall-order-service'

let pollTimer = null
let agentPollTimer = null
let agentEventSource = null
const pollingActive = ref(false)

const tabTitles = { dashboard: '概览', projects: '项目管理', repository: '仓库配置', pullRequests: 'PR 工作流', knowledge: 'RAG 知识库', reviews: '代码审查', agent: 'Agent 审批', aiLogs: 'AI 调用日志' }
const tabTitle = computed(() => tabTitles[tab.value] || 'RepoSage')
const repoBound = computed(() => commits.value.length > 0 || repoForm._bound)
const needsToken = computed(() => /^https?:\/\//i.test(repoForm.repoUrl.trim()))
const highRiskCount = computed(() => reports.value.filter(r => r.overallRisk === 'HIGH').length)
const filteredAgentRuns = computed(() => agentRuns.value.filter(runItem => {
  if (agentRunFilter.value === 'ALL') return true
  if (agentRunFilter.value === 'ACTIVE') return ['RECEIVED', 'PREPARING_REPOSITORY', 'ANALYZING_CHANGE', 'RETRIEVING_CONTEXT', 'PLANNING', 'EXECUTING_TOOLS', 'VERIFYING_FINDINGS', 'GENERATING_PATCH', 'VALIDATING_PATCH', 'PUBLISHING_RESULT'].includes(runItem.status)
  if (agentRunFilter.value === 'WAITING') return ['WAITING_APPROVAL', 'WAITING_EXTERNAL'].includes(runItem.status)
  if (agentRunFilter.value === 'FAILED') return ['FAILED', 'TIMED_OUT', 'DEAD'].includes(runItem.status)
  return ['SUCCEEDED', 'COMPLETED', 'CANCELED'].includes(runItem.status)
}))
const agentRunCounts = computed(() => ({
  active: agentRuns.value.filter(item => ['RECEIVED', 'PREPARING_REPOSITORY', 'ANALYZING_CHANGE', 'RETRIEVING_CONTEXT', 'PLANNING', 'EXECUTING_TOOLS', 'VERIFYING_FINDINGS', 'GENERATING_PATCH', 'VALIDATING_PATCH', 'PUBLISHING_RESULT'].includes(item.status)).length,
  waiting: agentRuns.value.filter(item => ['WAITING_APPROVAL', 'WAITING_EXTERNAL'].includes(item.status)).length,
  failed: agentRuns.value.filter(item => ['FAILED', 'TIMED_OUT', 'DEAD'].includes(item.status)).length,
  done: agentRuns.value.filter(item => ['SUCCEEDED', 'COMPLETED', 'CANCELED'].includes(item.status)).length,
}))
const prReports = computed(() => {
  if (!activePullRequest.value) return []
  const taskIds = new Set(tasks.value.filter(t => t.pullRequestId === activePullRequest.value.pullRequestId).map(t => t.taskId))
  return reports.value.filter(r => taskIds.has(r.taskId))
})

const SEV_ORDER = { HIGH: 0, MEDIUM: 1, LOW: 2, NONE: 3 }
const sortedIssues = computed(() => {
  if (!reportDetail.value) return []
  return [...reportDetail.value.issues].sort((a, b) => (SEV_ORDER[a.severity] ?? 9) - (SEV_ORDER[b.severity] ?? 9))
})
const severityTally = computed(() => {
  const t = { HIGH: 0, MEDIUM: 0, LOW: 0, NONE: 0 }
  if (reportDetail.value) for (const i of reportDetail.value.issues) t[i.severity] = (t[i.severity] || 0) + 1
  return t
})
const SEV_META = [
  { key: 'HIGH', label: '高', color: 'var(--risk-high)' },
  { key: 'MEDIUM', label: '中', color: 'var(--risk-medium)' },
  { key: 'LOW', label: '低', color: 'var(--risk-low)' },
]
const severityStrip = computed(() => {
  const t = severityTally.value
  const total = SEV_META.reduce((s, m) => s + (t[m.key] || 0), 0) || 1
  return SEV_META.map(m => ({ ...m, count: t[m.key] || 0, pct: (t[m.key] || 0) / total * 100 })).filter(m => m.count > 0)
})
function confClass(c) { return c >= 0.75 ? 'c-high' : c >= 0.5 ? 'c-mid' : 'c-low' }
function confText(c) { return c >= 0.75 ? '高置信' : c >= 0.5 ? '中等' : '较低' }

const groupedAiLogs = computed(() => {
  const byDate = new Map()
  for (const log of aiLogs.value) {
    const date = fmtDate(log.createdAt)
    if (!byDate.has(date)) byDate.set(date, [])
    byDate.get(date).push(log)
  }
  return [...byDate.entries()].map(([date, items]) => {
    const byTask = new Map()
    for (const l of items) {
      const key = l.taskId == null ? 'none' : String(l.taskId)
      if (!byTask.has(key)) byTask.set(key, { key, taskId: l.taskId ?? null, items: [] })
      byTask.get(key).items.push(l)
    }
    const taskGroups = [...byTask.values()].sort((a, b) => (b.taskId || 0) - (a.taskId || 0))
    return { date, relative: relativeDay(date), items, taskGroups }
  })
})

async function run(action, key) {
  if (key) busy[key] = true
  try {
    await action()
  } catch (error) {
    const msg = error?.message || '操作失败'
    toastMsg(msg, 'error')
    if (msg.includes('未登录') || msg.includes('401')) await logout(false)
  } finally {
    if (key) busy[key] = false
  }
}

/* ---------- auth ---------- */
async function login() {
  busy.auth = true
  try {
    await api('/auth/login', { method: 'POST', body: JSON.stringify(auth) })
    authenticated.value = true
    await afterLogin()
  } catch (error) {
    toastMsg(error?.message || '登录失败', 'error')
  } finally { busy.auth = false }
}
async function afterLogin() {
  await loadMe()
  await refreshAll()
  tab.value = 'dashboard'
  toastMsg('登录成功', 'success')
}
async function logout(callApi = true) {
  if (callApi) {
    try { await api('/auth/logout', { method: 'POST' }) } catch { /* ignore */ }
  }
  authenticated.value = false
  Object.assign(me, { userId: null, username: '', nickname: '', role: '' })
  projects.value = []
  activeProject.value = null
  resetReviewState()
  stopPolling()
}
async function loadMe() {
  try {
    const data = await api('/auth/me')
    Object.assign(me, data)
  } catch {
    Object.assign(me, { userId: null, username: '', nickname: '', role: '' })
    throw new Error('未登录')
  }
}

/* ---------- refresh ---------- */
async function refreshAll() {
  if (!authenticated.value) return
  busy.refresh = true
  try {
    await loadProjects()
    if (activeProject.value) {
      await Promise.allSettled([loadRepository(), loadDocuments(), loadReviews(), loadPullRequests(), loadAiLogs()])
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
function askDeleteProject(p) {
  confirmModal.value = {
    title: `删除项目「${p.name}」？`,
    body: '将级联删除该项目的仓库绑定、知识库、审查任务、报告、问题与反馈，操作不可恢复。',
    onConfirm: async () => {
      await api(`/projects/${p.projectId}`, { method: 'DELETE' })
      if (activeProject.value && activeProject.value.projectId === p.projectId) activeProject.value = null
      await loadProjects()
      toastMsg('项目已删除', 'success')
    },
  }
}

/* ---------- repository ---------- */
function useDemoRepository() {
  repoForm.repoUrl = demoRepoPath
  repoForm.provider = 'LOCAL'
  repoForm.defaultBranch = activeProject.value?.defaultBranch || 'main'
  repoForm.accessToken = ''
}
async function loadRepository() {
  if (!activeProject.value) return
  try {
    const repo = await api(`/projects/${activeProject.value.projectId}/repository`)
    if (repo && repo.repoUrl) {
      repoForm.repoUrl = repo.repoUrl
      if (repo.provider) repoForm.provider = repo.provider
      repoForm.defaultBranch = repo.defaultBranch || repoForm.defaultBranch
      repoForm.accessToken = ''
      repoForm._bound = true
      repoForm._tokenConfigured = !!repo.tokenConfigured
    }
  } catch {
    // 未绑定(detail 返回 404)或读取失败:保留当前表单，不清空用户正在填写的内容
  }
}
async function bindRepository() {
  if (!repoForm.repoUrl.trim()) return toastMsg('请填写 Git 地址', 'error')
  busy.bind = true
  try {
    // 只发后端 BindRepositoryRequest 声明的字段,避免把 UI 内部标志(_bound/_tokenConfigured)一并 POST。
    const body = JSON.stringify({
      repoUrl: repoForm.repoUrl,
      provider: repoForm.provider,
      defaultBranch: repoForm.defaultBranch,
      accessToken: repoForm.accessToken,
    })
    await api(`/projects/${activeProject.value.projectId}/repository`, { method: 'POST', body })
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

/* ---------- pull requests ---------- */
function resetPullRequestForm() {
  Object.assign(pullRequestForm, {
    pullRequestId: null,
    prNumber: null,
    title: '',
    authorName: '',
    sourceBranch: '',
    targetBranch: activeProject.value?.defaultBranch || 'main',
    baseSha: '',
    headSha: '',
    provider: 'GITHUB',
    externalPrId: '',
    status: 'OPEN',
  })
}
function fillPrFromSelectedCommit() {
  if (!selectedCommit.value) return
  Object.assign(pullRequestForm, {
    title: selectedCommit.value.message || `Review ${shortCommit(selectedCommit.value.commitId)}`,
    authorName: selectedCommit.value.authorName || '',
    sourceBranch: reviewForm.branch || activeProject.value?.defaultBranch || 'main',
    targetBranch: activeProject.value?.defaultBranch || 'main',
    baseSha: selectedCommit.value.parentCommitId || '',
    headSha: selectedCommit.value.commitId,
  })
  tab.value = 'pullRequests'
}
async function savePullRequest() {
  if (!pullRequestForm.title.trim()) return toastMsg('请填写 PR 标题', 'error')
  if (!pullRequestForm.sourceBranch.trim() || !pullRequestForm.targetBranch.trim()) return toastMsg('请填写源分支和目标分支', 'error')
  if (!pullRequestForm.baseSha.trim() || !pullRequestForm.headSha.trim()) return toastMsg('请填写 Base 和 Head', 'error')
  busy.pullRequest = true
  try {
    const payload = {
      prNumber: pullRequestForm.prNumber || null,
      title: pullRequestForm.title,
      authorName: pullRequestForm.authorName,
      sourceBranch: pullRequestForm.sourceBranch,
      targetBranch: pullRequestForm.targetBranch,
      baseSha: pullRequestForm.baseSha,
      headSha: pullRequestForm.headSha,
      provider: pullRequestForm.provider,
      externalPrId: pullRequestForm.externalPrId,
    }
    if (pullRequestForm.pullRequestId) {
      // status 仅 UpdatePullRequestRequest 有;创建端点(CreatePullRequestRequest)无该字段。
      const body = JSON.stringify({ ...payload, status: pullRequestForm.status })
      await api(`/projects/${activeProject.value.projectId}/pull-requests/${pullRequestForm.pullRequestId}`, { method: 'PUT', body })
      toastMsg('PR 已更新', 'success')
    } else {
      const body = JSON.stringify(payload)
      const created = await api(`/projects/${activeProject.value.projectId}/pull-requests`, { method: 'POST', body })
      activePullRequest.value = created
      toastMsg('PR 已登记', 'success')
    }
    await loadPullRequests()
    resetPullRequestForm()
  } finally { busy.pullRequest = false }
}
async function loadPullRequests() {
  if (!activeProject.value) return
  busy.pullRequests = true
  try {
    pullRequests.value = await api(`/projects/${activeProject.value.projectId}/pull-requests`)
    if (activePullRequest.value) {
      activePullRequest.value = pullRequests.value.find(pr => pr.pullRequestId === activePullRequest.value.pullRequestId) || null
    }
    if (!activePullRequest.value && pullRequests.value.length) {
      await selectPullRequest(pullRequests.value[0])
    }
  } finally { busy.pullRequests = false }
}
async function selectPullRequest(pr) {
  activePullRequest.value = pr
  prActionForm.reportId = null
  actionReportDetail.value = null
  actionIssueIds.value = new Set()
  await loadPrActions()
}
function editPullRequest(pr) {
  Object.assign(pullRequestForm, {
    pullRequestId: pr.pullRequestId,
    prNumber: pr.prNumber,
    title: pr.title,
    authorName: pr.authorName || '',
    sourceBranch: pr.sourceBranch,
    targetBranch: pr.targetBranch,
    baseSha: pr.baseSha,
    headSha: pr.headSha,
    provider: pr.provider,
    externalPrId: pr.externalPrId || '',
    status: pr.status,
  })
  toastMsg('正在编辑 PR：' + (pr.prNumber ? '#' + pr.prNumber : '#' + pr.pullRequestId))
}
async function createPrReview() {
  if (!activePullRequest.value) return
  busy.prReview = true
  try {
    const documentIds = Array.from(chosenDocs.value)
    await api(`/projects/${activeProject.value.projectId}/pull-requests/${activePullRequest.value.pullRequestId}/review-task`, {
      method: 'POST',
      body: JSON.stringify({ documentIds }),
    })
    await loadReviews()
    if (prReports.value[0]) await selectPrReport(prReports.value[0].reportId)
    toastMsg('PR 审查任务已创建', 'success')
    maybeStartPolling()
  } finally { busy.prReview = false }
}
async function selectPrReport(reportId) {
  prActionForm.reportId = reportId
  await loadActionReport()
}
async function loadActionReport() {
  actionIssueIds.value = new Set()
  if (!prActionForm.reportId) {
    actionReportDetail.value = null
    return
  }
  actionReportDetail.value = await api(`/projects/${activeProject.value.projectId}/reviews/reports/${prActionForm.reportId}`)
}
function toggleActionIssue(id) {
  const next = new Set(actionIssueIds.value)
  next.has(id) ? next.delete(id) : next.add(id)
  actionIssueIds.value = next
}
function selectBlockingIssues() {
  if (!actionReportDetail.value) return
  actionIssueIds.value = new Set(actionReportDetail.value.issues
    .filter(i => i.severity === 'HIGH' || i.severity === 'MEDIUM')
    .map(i => i.issueId))
}
function clearActionIssues() { actionIssueIds.value = new Set() }
async function submitPrAction() {
  if (!activePullRequest.value) return
  busy.prAction = true
  try {
    await api(`/projects/${activeProject.value.projectId}/pull-requests/${activePullRequest.value.pullRequestId}/actions`, {
      method: 'POST',
      body: JSON.stringify({
        actionType: prActionForm.actionType,
        reportId: prActionForm.reportId,
        reason: prActionForm.reason,
        requirement: prActionForm.requirement,
        selectedIssueIds: Array.from(actionIssueIds.value),
      }),
    })
    Object.assign(prActionForm, { actionType: 'REQUEST_CHANGES', reportId: null, reason: '', requirement: '' })
    actionReportDetail.value = null
    actionIssueIds.value = new Set()
    await Promise.allSettled([loadPullRequests(), loadPrActions()])
    toastMsg('审核动作已提交', 'success')
  } finally { busy.prAction = false }
}
async function loadPrActions() {
  if (!activePullRequest.value) {
    prActions.value = []
    return
  }
  prActions.value = await api(`/projects/${activeProject.value.projectId}/pull-requests/${activePullRequest.value.pullRequestId}/actions`)
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
async function reindexKnowledge() {
  const r = await api(`/projects/${activeProject.value.projectId}/knowledge/reindex`, { method: 'POST' })
  await loadDocuments()
  toastMsg(`索引已重建：${r.indexedDocuments}/${r.totalDocuments} 篇` + (r.failedDocuments ? `，${r.failedDocuments} 失败` : ''), 'success')
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
  pullRequests.value = []; activePullRequest.value = null; prActions.value = []; actionReportDetail.value = null; actionIssueIds.value = new Set()
  Object.assign(repoForm, { repoUrl: '', provider: 'GITHUB', defaultBranch: activeProject.value?.defaultBranch || 'main', accessToken: '', _bound: false, _tokenConfigured: false })
  chosenDocs.value = new Set()
  resetPullRequestForm()
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
    if (activeTask.value) activeTask.value = tasks.value.find(t => t.taskId === activeTask.value.taskId) || null
  } finally { busy.reviews = false }
}
function selectTask(t) { activeTask.value = t; mqLogs.value = [] }
async function openReport(reportId) {
  reportDetail.value = await api(`/projects/${activeProject.value.projectId}/reviews/reports/${reportId}`)
  tab.value = 'reviews'
}
async function loadMqLogs(taskId) { mqLogs.value = await api(`/mq/logs?taskId=${taskId}`) }

async function cancelTask(t) {
  await api(`/projects/${activeProject.value.projectId}/reviews/tasks/${t.taskId}/cancel`, { method: 'POST' })
  await loadReviews()
  toastMsg('任务已停止', 'success')
}
function askDeleteTask(t) {
  confirmModal.value = {
    title: `删除审查任务 #${t.taskId}？`,
    body: '将一并删除该任务生成的报告、问题、反馈以及关联的 AI / MQ 日志，操作不可恢复。',
    onConfirm: async () => {
      await api(`/projects/${activeProject.value.projectId}/reviews/tasks/${t.taskId}`, { method: 'DELETE' })
      if (activeTask.value && activeTask.value.taskId === t.taskId) activeTask.value = null
      const reportForTask = reports.value.find(r => r.taskId === t.taskId)
      if (reportDetail.value && reportForTask && reportDetail.value.reportId === reportForTask.reportId) reportDetail.value = null
      await loadReviews()
      toastMsg('任务已删除', 'success')
    },
  }
}
function askDeleteReport(r) {
  confirmModal.value = {
    title: `删除审查报告 #${r.reportId}？`,
    body: '将删除该报告及其下的所有问题与反馈，对应的审查任务会保留，操作不可恢复。',
    onConfirm: async () => {
      await api(`/projects/${activeProject.value.projectId}/reviews/reports/${r.reportId}`, { method: 'DELETE' })
      if (reportDetail.value && reportDetail.value.reportId === r.reportId) reportDetail.value = null
      await loadReviews()
      toastMsg('报告已删除', 'success')
    },
  }
}
async function confirmAction() {
  if (!confirmModal.value) return
  busy.confirm = true
  try {
    await confirmModal.value.onConfirm()
    confirmModal.value = null
  } finally { busy.confirm = false }
}

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

/* ---------- feedback (one verdict per user, upsert) ---------- */
function ensureDraft(id) { if (!fbDraft[id]) fbDraft[id] = { comment: '' }; return fbDraft[id] }
function myVote(issueId) {
  const list = feedbackMap[issueId] || []
  return list.find(fb => fb.mine) || null
}
function voteClass(issueId, type) {
  const mine = myVote(issueId)
  return mine && mine.feedbackType === type ? 'on-' + type : ''
}
function feedbackCount(issueId) {
  const n = (feedbackMap[issueId] || []).length
  return n ? ` (${n})` : ''
}
async function toggleFeedback(id) {
  openFeedback[id] = !openFeedback[id]
  if (openFeedback[id]) await loadFeedback(id)
}
async function loadFeedback(id) {
  feedbackMap[id] = await api(`/review-issues/${id}/feedback`)
  const mine = myVote(id)
  ensureDraft(id).comment = mine?.comment || ''
}
async function vote(issueId, type) {
  const draft = ensureDraft(issueId)
  await api(`/review-issues/${issueId}/feedback`, { method: 'POST', body: JSON.stringify({ feedbackType: type, comment: draft.comment || '' }) })
  openFeedback[issueId] = true
  await loadFeedback(issueId)
  toastMsg('反馈已保存', 'success')
}
async function submitFeedbackForm(issueId) {
  const mine = myVote(issueId)
  if (!mine) return toastMsg('请先选择一个投票', 'error')
  const d = ensureDraft(issueId)
  await api(`/review-issues/${issueId}/feedback`, { method: 'POST', body: JSON.stringify({ feedbackType: mine.feedbackType, comment: d.comment }) })
  await loadFeedback(issueId)
  toastMsg('反馈说明已更新', 'success')
}
async function removeMyFeedback(issueId) {
  await api(`/review-issues/${issueId}/feedback`, { method: 'DELETE' })
  ensureDraft(issueId).comment = ''
  await loadFeedback(issueId)
  toastMsg('已撤回你的反馈', 'success')
}

/* ---------- ai logs ---------- */
async function loadAiLogs(taskId = null) {
  if (!activeProject.value) return
  const query = taskId ? `taskId=${taskId}&limit=100` : `projectId=${activeProject.value.projectId}&limit=100`
  aiLogs.value = await api(`/ai/logs?${query}`)
  aiLogScope.value = taskId ? `任务 #${taskId} 维度` : '项目维度'
}

async function loadAgentWorkspace() {
  if (!activeProject.value || !agentRunId.value) return
  busy.agent = true
  try {
    const [timeline, patches] = await Promise.all([
      api(`/agent-runs/${agentRunId.value}/timeline`),
      api(`/projects/${activeProject.value.projectId}/agent-runs/${agentRunId.value}/patches`),
    ])
    agentTimeline.value = timeline.steps || []
    agentRunDetail.value = timeline.run || null
    agentPatch.value = patches.length ? { ...patches[patches.length - 1], downloadUrl: `data:text/x-diff;charset=utf-8,${encodeURIComponent(patches[patches.length - 1].patchContent || '')}` } : null
    agentFindings.value = (agentPatch.value?.findingIds || []).map(id => ({ id, severity: 'INFO', title: `Finding #${id}`, description: '详见持久化证据与置信度记录', evidence: [] }))
    if (!agentHeadSha.value) agentHeadSha.value = timeline.run?.headSha || agentPatch.value?.headSha || ''
    if (timeline.run?.terminal) stopAgentPolling()
    else startAgentPolling()
  } finally { busy.agent = false }
}
async function loadAgentRuns() {
  if (!activeProject.value) return
  busy.agentRuns = true
  try {
    agentRuns.value = await api(`/agent-runs/project/${activeProject.value.projectId}`)
    if (!agentRunId.value && agentRuns.value.length) {
      agentRunId.value = agentRuns.value[0].id
      agentHeadSha.value = agentRuns.value[0].headSha || ''
    }
  } finally { busy.agentRuns = false }
}
async function selectAgentRun() {
  const selected = agentRuns.value.find(item => item.id === agentRunId.value)
  if (selected) agentHeadSha.value = selected.headSha || ''
  if (agentRunId.value) await loadAgentWorkspace()
}
async function openAgentWorkspace() {
  tab.value = 'agent'
  await run(loadAgentRuns)
  if (agentRunId.value) await run(loadAgentWorkspace)
}
function startAgentPolling() {
  if (!agentRunId.value) return
  agentPolling.value = true
  openAgentEvents() // 优先 SSE 实时推送(每次同步到当前 run)
  if (agentPollTimer) return
  agentPollTimer = setInterval(async () => { // 轮询兜底:SSE 不可用/断开时仍能更新
    if (tab.value !== 'agent' || busy.agent) return
    try { await loadAgentWorkspace() } catch { stopAgentPolling() }
  }, 8000)
}
function openAgentEvents() {
  if (!agentRunId.value) return
  if (agentEventSource && agentEventSource._runId === agentRunId.value) return
  if (agentEventSource) { agentEventSource.close(); agentEventSource = null }
  try {
    const es = new EventSource(`/api/agent-runs/${agentRunId.value}/events`)
    es._runId = agentRunId.value
    es.onmessage = () => { if (tab.value === 'agent' && !busy.agent) loadAgentWorkspace().catch(() => {}) }
    agentEventSource = es
  } catch { agentEventSource = null }
}
function stopAgentPolling() {
  if (agentPollTimer) clearInterval(agentPollTimer)
  agentPollTimer = null
  if (agentEventSource) { agentEventSource.close(); agentEventSource = null }
  agentPolling.value = false
}
async function onPatchDecided() { toastMsg('Patch 审批决定已记录', 'success'); await loadAgentWorkspace() }
async function cancelAgentRun() {
  if (!agentRunId.value) return
  busy.agentControl = true
  try { await api(`/agent-runs/${agentRunId.value}/cancel`, { method: 'POST' }); await loadAgentWorkspace(); toastMsg('Agent Run 已取消', 'success') }
  finally { busy.agentControl = false }
}
function askCancelAgentRun() {
  confirmModal.value = {
    title: `取消 Agent Run #${agentRunId.value}？`,
    body: '当前步骤将停止并进入 CANCELED。Timeline、模型调用、工具调用、Finding、Patch 和发布审计记录都会保留。',
    confirmLabel: '确认取消',
    onConfirm: cancelAgentRun,
  }
}
async function retryAgentRun() {
  if (!agentRunId.value) return
  busy.agentControl = true
  try { await api(`/agent-runs/${agentRunId.value}/retry`, { method: 'POST' }); await loadAgentWorkspace(); toastMsg('失败步骤已重新入队', 'success') }
  finally { busy.agentControl = false }
}
function askRetryAgentRun() {
  confirmModal.value = {
    title: `重试 Agent Run #${agentRunId.value} 的失败步骤？`,
    body: '系统会复用当前 Agent Run 和幂等边界重新入队失败步骤，可能再次调用模型、工具或 Sandbox 并产生额外耗时与成本。',
    confirmLabel: '确认重试',
    onConfirm: retryAgentRun,
  }
}
function onPatchError(error) { toastMsg(error?.message || 'Patch 审批失败', 'error') }
async function openProjectAiLogs() { if (!activeProject.value) return; await loadAiLogs(); tab.value = 'aiLogs' }
async function openTaskAiLogs(taskId) { await loadAiLogs(taskId); tab.value = 'aiLogs' }
function toggleDate(date) { collapsedDates[date] = !collapsedDates[date] }

/* ---------- helpers ---------- */
function relativeDay(dateStr) {
  const today = fmtDate(new Date().toISOString())
  const y = new Date(); y.setDate(y.getDate() - 1)
  const yesterday = fmtDate(y.toISOString())
  if (dateStr === today) return '今天'
  if (dateStr === yesterday) return '昨天'
  return ''
}
function statusLabel(s) { return { PENDING: '等待中', RUNNING: '运行中', SUCCESS: '成功', FAILED: '失败', DEAD: '已死信', CANCELED: '已停止' }[s] || s }
function prStateLabel(s) { return { PENDING: '待审查', PASSED: '已通过', CHANGES_REQUESTED: '已打回', WAIVED: '已豁免' }[s] || s }
function actionLabel(s) { return { APPROVE: '通过', REQUEST_CHANGES: '打回', WAIVE: '豁免', COMMENT: '评论' }[s] || s }
function actionStateClass(s) { return { APPROVE: 'SUCCESS', REQUEST_CHANGES: 'FAILED', WAIVE: 'PENDING', COMMENT: 'CONSUMED' }[s] || s }
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

function focusEvidenceAnchor() {
  const prefix = '#agent-evidence='
  if (!window.location.hash.startsWith(prefix)) return
  const location = decodeURIComponent(window.location.hash.slice(prefix.length))
  const separator = location.lastIndexOf(':')
  const path = separator > 0 ? location.slice(0, separator) : location
  const target = document.querySelector(`[data-evidence-path="${CSS.escape(path)}"]`)
  if (!target) return
  target.scrollIntoView({ behavior: 'smooth', block: 'center' })
  target.classList.add('evidence-focus')
  setTimeout(() => target.classList.remove('evidence-focus'), 1800)
}
onMounted(async () => {
  window.addEventListener('hashchange', focusEvidenceAnchor)
  try {
    await loadMe()
    authenticated.value = !!me.userId
    if (authenticated.value) {
      await refreshAll()
    }
  } catch {
    authenticated.value = false
  }
})
onUnmounted(() => { stopPolling(); stopAgentPolling(); window.removeEventListener('hashchange', focusEvidenceAnchor) })
</script>
