import { createRouter, createWebHashHistory } from 'vue-router'

// Hash 模式:生产由 Nginx 托管静态文件,hash 路由无需 history fallback 配置;
// 且与既有 "#agent-evidence=" 证据锚点(SCM 评论外链)最容易并存。
// 视图组件在 T3~T7 逐个拆出前,由 App.vue 依据路由名渲染对应 tab(过渡态)。
export const TAB_ROUTES = [
  { path: '/dashboard', name: 'dashboard' },
  { path: '/projects', name: 'projects' },
  { path: '/repository', name: 'repository' },
  { path: '/pull-requests', name: 'pullRequests' },
  { path: '/knowledge', name: 'knowledge' },
  { path: '/reviews', name: 'reviews' },
  { path: '/agent', name: 'agent' },
  { path: '/ai-logs', name: 'aiLogs' },
]

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    ...TAB_ROUTES,
    // 旧的 "#agent-evidence=..." 锚点不是路由:重定向到 agent 页并把定位信息
    // 转成 query,由 Agent 工作台完成滚动定位(见 App.vue focusEvidenceAnchor)。
    {
      path: '/agent-evidence=:location(.*)',
      redirect: to => ({ name: 'agent', query: { evidence: to.params.location } }),
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})
