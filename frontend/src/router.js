import { createRouter, createWebHashHistory } from 'vue-router'
import { registerNav } from './nav.js'
import DashboardView from './views/DashboardView.vue'
import ProjectsView from './views/ProjectsView.vue'
import RepositoryView from './views/RepositoryView.vue'
import PullRequestsView from './views/PullRequestsView.vue'
import KnowledgeView from './views/KnowledgeView.vue'
import ReviewsView from './views/ReviewsView.vue'
import AgentView from './views/AgentView.vue'
import AiLogsView from './views/AiLogsView.vue'
import InkAtelierPage from './pages/InkAtelierPage.vue'

// Hash 模式:生产由 Nginx 托管静态文件,hash 路由无需 history fallback 配置;
// 且与既有 "#agent-evidence=" 证据锚点(SCM 评论外链)最容易并存。
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/dashboard', name: 'dashboard', component: DashboardView },
    { path: '/projects', name: 'projects', component: ProjectsView },
    { path: '/repository', name: 'repository', component: RepositoryView },
    { path: '/pull-requests', name: 'pullRequests', component: PullRequestsView },
    { path: '/knowledge', name: 'knowledge', component: KnowledgeView },
    { path: '/reviews', name: 'reviews', component: ReviewsView },
    { path: '/agent', name: 'agent', component: AgentView },
    { path: '/ai-logs', name: 'aiLogs', component: AiLogsView },
    // 墨境书院隔离入口(重构步骤 5):meta.shell 让 App.vue 跳过旧壳层,
    // 页面自带 LoginGate 门禁与新 AppShell;旧路由与旧壳层保持原样并存。
    { path: '/ink', name: 'inkAtelier', component: InkAtelierPage, meta: { shell: 'ink' } },
    // 旧的 "#agent-evidence=..." 锚点不是路由:重定向到 agent 页并把定位信息
    // 转成 query,由 Agent 工作台完成滚动定位(见 useAgentWorkspace.focusEvidenceAnchor)。
    {
      path: '/agent-evidence=:location(.*)',
      redirect: to => ({ name: 'agent', query: { evidence: to.params.location } }),
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})

registerNav({
  name: () => (typeof router.currentRoute.value.name === 'string' ? router.currentRoute.value.name : 'dashboard'),
  query: () => router.currentRoute.value.query,
  push: to => router.push(to),
})
