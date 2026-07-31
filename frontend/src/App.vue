<template>
  <LoginView v-if="!authenticated" @authenticated="afterLogin" />

  <AppShell v-else @navigate="onNavigate" @refresh="run(refreshAll)" @logout="logout">
      <!-- ============ DASHBOARD ============ -->
      <template v-if="tab === 'dashboard'">
        <DashboardStats :project-count="projects.length" :task-count="tasks.length" :report-count="reports.length" :high-risk="highRiskCount" />

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
            <button @click="run(saveProject, 'project')" :disabled="busy.project">
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

              <KnowledgeDocPicker v-model="prDocs" :documents="documents" compact
                title="参与 PR 审查的知识库"
                empty-hint="该项目暂无知识库文档；不选则审查仅基于 PR diff。" />

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

          <KnowledgeDocPicker v-model="reviewDocs" :documents="documents" show-meta show-count
            title="参与审查的知识库"
            empty-hint="该项目暂无知识库文档；不选则审查仅基于代码 diff。可在“知识库”页上传。" />

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
            <div class="head-actions">
              <button class="sm secondary" :disabled="busy.export" @click="run(() => exportReport('markdown'), 'export')">导出 Markdown</button>
              <button class="sm secondary" :disabled="busy.export" @click="run(() => exportReport('sarif'), 'export')">导出 SARIF</button>
              <button class="sm danger" @click="askDeleteReport(reportDetail)">删除报告</button>
            </div>
          </div>

          <ReportSummary :report="reportDetail" />

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
  </AppShell>
</template>

<script setup>
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fmtDate, fmtTime, shortCommit } from './utils/format'
import { statusLabel, prStateLabel, actionLabel, actionStateClass, mqStatusClass, fbLabel, fbBadge, confClass, confText, diffLines } from './utils/labels'
import { initCsrf, setUnauthorizedHandler } from './api/client'
import { useSession } from './composables/useSession'
import { useBusy } from './composables/useBusy'
import { useToast } from './composables/useToast'
import { useProjects } from './composables/useProjects'
import { useRepository } from './composables/useRepository'
import { useReviews } from './composables/useReviews'
import { useFeedback } from './composables/useFeedback'
import { usePullRequests } from './composables/usePullRequests'
import { useKnowledge } from './composables/useKnowledge'
import { useAgentWorkspace } from './composables/useAgentWorkspace'
import { useAiLogs } from './composables/useAiLogs'
import { useWorkspace } from './composables/useWorkspace'
import AgentReviewWorkspace from './components/agent/AgentReviewWorkspace.vue'
import AppShell from './components/AppShell.vue'
import LoginView from './views/LoginView.vue'
import DashboardViz from './components/DashboardViz.vue'
import DashboardStats from './components/DashboardStats.vue'
import ReportSummary from './components/ReportSummary.vue'
import KnowledgeDocPicker from './components/KnowledgeDocPicker.vue'

// App.vue 只做装配:领域状态与动作全部来自单例 composable,
// 跨域动作(跳转/联动)来自 useWorkspace。视图模板将在后续步骤迁往 views/。
const { authenticated, me, projects, activeProject } = useSession()
const { busy, run } = useBusy()
const { toastMsg } = useToast()
const { projectForm, resetProjectForm, editProject, saveProject, askDeleteProject } = useProjects()
const { repoForm, commits, selectedCommit, diffFiles, repoBound, needsToken, useDemoRepository, bindRepository, unbindRepository, loadCommits } = useRepository()
const { reviewForm, tasks, reports, activeTask, reportDetail, mqLogs, pollingActive, highRiskCount, sortedIssues, createReview, loadReviews, selectTask, loadMqLogs, cancelTask, askDeleteTask, exportReport, askDeleteReport, stopPolling } = useReviews()
const { openFeedback, feedbackMap, ensureDraft, myVote, voteClass, feedbackCount, toggleFeedback, vote, submitFeedbackForm, removeMyFeedback } = useFeedback()
const { pullRequestForm, prActionForm, pullRequests, activePullRequest, prActions, actionReportDetail, actionIssueIds, prReports, resetPullRequestForm, savePullRequest, loadPullRequests, selectPullRequest, editPullRequest, createPrReview, selectPrReport, loadActionReport, toggleActionIssue, selectBlockingIssues, clearActionIssues, submitPrAction } = usePullRequests()
const { documents, docType, searchQuery, searchMatches, searched, reviewDocs, prDocs, onFileChange, uploadDocument, reindexKnowledge, deleteDocument, searchKnowledge } = useKnowledge()
const { agentRuns, agentRunId, agentRunFilter, agentHeadSha, agentTimeline, agentFindings, agentPatch, agentRunDetail, agentPolling, filteredAgentRuns, agentRunCounts, loadAgentWorkspace, loadAgentRuns, selectAgentRun, stopAgentPolling, onPatchDecided, onPatchError, askCancelAgentRun, askRetryAgentRun } = useAgentWorkspace()
const { aiLogs, aiLogScope, selectedAiLog, collapsedDates, groupedAiLogs, toggleDate } = useAiLogs()
const { afterLogin, logout, loadMe, refreshAll, goTab, selectProject, selectCommit, loadDiff, reviewSelectedCommit, fillPrFromSelectedCommit, openReport, openAgentWorkspace, openProjectAiLogs, openTaskAiLogs } = useWorkspace()

const route = useRoute()
const router = useRouter()
// 过渡态桥接:tab 的唯一事实源是路由,模板里的 `tab === 'x'` 读保持可用;
// 视图迁往 views/ 后,读侧会被 <router-view> 取代。
const tab = computed({
  get: () => (typeof route.name === 'string' ? route.name : 'dashboard'),
  set: name => { router.push({ name }) },
})

// 侧边导航统一入口:agent / aiLogs 各有装载动作,其余按需刷新或直切。
function onNavigate(name) {
  if (name === 'agent') return openAgentWorkspace()
  if (name === 'aiLogs') return openProjectAiLogs()
  if (name === 'dashboard' || name === 'projects') { tab.value = name; return }
  goTab(name)
}

// 会话失效只处理一次:并发请求同时 401 时,第一个把 authenticated 置 false,其余直接短路。
setUnauthorizedHandler(() => {
  if (!authenticated.value) return
  logout(false)
  toastMsg('登录已过期，请重新登录', 'error')
})

onMounted(async () => {
  // 先做 CSRF 引导:拿到开关状态与 Cookie 名,之后的写请求才知道要不要带 X-XSRF-TOKEN。
  await initCsrf()
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
onUnmounted(() => { stopPolling(); stopAgentPolling() })
</script>
