import { createRouter, createWebHashHistory } from 'vue-router'
import { registerNav } from './nav.js'
import InkDashboardPage from './pages/InkDashboardPage.vue'
import InkProjectsPage from './pages/InkProjectsPage.vue'
import InkRepositoryPage from './pages/InkRepositoryPage.vue'
import InkRequirementsPage from './pages/InkRequirementsPage.vue'
import InkAgentPage from './pages/InkAgentPage.vue'
import InkQualityPage from './pages/InkQualityPage.vue'
import InkKnowledgePage from './pages/InkKnowledgePage.vue'
import InkMetricsPage from './pages/InkMetricsPage.vue'

function compatibilityRedirect(name, forcedQuery = {}) {
  return to => ({ name, query: { ...to.query, ...forcedQuery } })
}

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/dashboard', name: 'dashboard', component: InkDashboardPage, meta: { shell: 'ink' } },
    { path: '/projects', name: 'projects', component: InkProjectsPage, meta: { shell: 'ink' } },
    { path: '/requirements', name: 'requirements', component: InkRequirementsPage, meta: { shell: 'ink' } },
    { path: '/repository', name: 'repository', component: InkRepositoryPage, meta: { shell: 'ink' } },
    { path: '/agent', name: 'agent', component: InkAgentPage, meta: { shell: 'ink' } },
    { path: '/quality', name: 'quality', component: InkQualityPage, meta: { shell: 'ink' } },
    { path: '/knowledge', name: 'knowledge', component: InkKnowledgePage, meta: { shell: 'ink' } },
    { path: '/metrics', name: 'metrics', component: InkMetricsPage, meta: { shell: 'ink' } },
    { path: '/pull-requests', redirect: compatibilityRedirect('repository', { section: 'pull-requests' }) },
    { path: '/reviews', redirect: compatibilityRedirect('agent', { section: 'reviews' }) },
    { path: '/ai-logs', redirect: compatibilityRedirect('metrics', { section: 'ai' }) },
    { path: '/ink', redirect: { name: 'dashboard' } },
    { path: '/agent-evidence=:location(.*)', redirect: to => ({ path: '/agent', query: { ...to.query, section: 'agent', evidence: to.params.location } }) },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})
registerNav({ name: () => (typeof router.currentRoute.value.name === 'string' ? router.currentRoute.value.name : 'dashboard'), query: () => router.currentRoute.value.query, push: to => router.push(to) })
