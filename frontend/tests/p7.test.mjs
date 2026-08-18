import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { workbenchTarget } from '../src/features/dashboard/workbenchTarget.js'
import { formatDuration, formatRate, metricEntries } from '../src/features/metrics/metricsModel.js'
import { INK_NAV } from '../src/features/shell/inkNav.js'

test('workbench targets map to canonical pages and preserve object identity', () => {
  assert.deepEqual(workbenchTarget({ type: 'REQUIREMENT', requirementId: 4 }), { name: 'requirements', query: { requirementId: '4' } })
  assert.deepEqual(workbenchTarget({ type: 'FINDING', findingId: 5 }), { name: 'quality', query: { findingId: '5' } })
  assert.deepEqual(workbenchTarget({ type: 'PULL_REQUEST', pullRequestId: 6 }), { name: 'repository', query: { section: 'pull-requests', pullRequestId: '6' } })
  assert.deepEqual(workbenchTarget({ targetType: 'AGENT_RUN', objectId: 7 }), { name: 'agent', query: { section: 'agent', runId: '7' } })
  assert.deepEqual(workbenchTarget({ targetType: 'REVIEW_REPORT', objectId: 8 }), { name: 'agent', query: { section: 'reviews', reportId: '8' } })
  assert.equal(workbenchTarget({ type: 'UNKNOWN' }), null)
})

test('metrics helpers distinguish no-sample values and keep distributions inspectable', () => {
  assert.equal(formatDuration(null), '无样本')
  assert.equal(formatDuration(850), '850 ms')
  assert.equal(formatDuration(1250), '1.3 s')
  assert.equal(formatDuration(120000), '2.0 min')
  assert.equal(formatRate(null), '无样本')
  assert.equal(formatRate(.875), '87.5%')
  assert.deepEqual(metricEntries({ LOW: 2, HIGH: 5 }), [['HIGH', 5], ['LOW', 2]])
})

test('P7 router keeps eight canonical routes and query-preserving compatibility redirects', async () => {
  const router = await readFile(new URL('../src/router.js', import.meta.url), 'utf8')
  assert.equal(INK_NAV.length, 8)
  assert.match(router, /query: \{ \.\.\.to\.query, \.\.\.forcedQuery \}/)
  assert.match(router, /compatibilityRedirect\('repository', \{ section: 'pull-requests' \}\)/)
  assert.match(router, /compatibilityRedirect\('agent', \{ section: 'reviews' \}\)/)
  assert.match(router, /compatibilityRedirect\('metrics', \{ section: 'ai' \}\)/)
  assert.match(router, /path: '\/ink', redirect: \{ name: 'dashboard' \}/)
})

test('legacy shell and routed legacy views have zero filesystem consumers', async () => {
  for (const file of ['../src/components/AppShell.vue','../src/views/LoginView.vue','../src/views/AgentView.vue','../src/views/ReviewsView.vue','../src/views/PullRequestsView.vue','../src/views/KnowledgeView.vue','../src/views/AiLogsView.vue','../src/pages/InkAtelierPage.vue']) await assert.rejects(() => readFile(new URL(file, import.meta.url), 'utf8'))
  const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
  const router = await readFile(new URL('../src/router.js', import.meta.url), 'utf8')
  assert.doesNotMatch(app + router, /AppShell|LoginView|InkAtelierPage|AgentView|ReviewsView|PullRequestsView|KnowledgeView|AiLogsView/)
})

test('workspace resets P7 projections after the pinned existing teardown order', async () => {
  const source = await readFile(new URL('../src/composables/useWorkspace.js', import.meta.url), 'utf8')
  const reset = source.slice(source.indexOf('function resetForProject'), source.indexOf('function selectProject'))
  const order = ['repository.reset()', 'reviews.reset()', 'pullRequests.reset()', 'agent.reset()', 'knowledge.chosenDocsReset()', 'aiLogs.reset()', 'requirementAssistant.reset()', 'workbench.reset()', 'developmentMetrics.reset()']
  let cursor = -1
  for (const call of order) { const next = reset.indexOf(call); assert.ok(next > cursor, `${call} 必须保持 reset 顺序`); cursor = next }
  assert.match(source, /name: 'metrics', query: \{ section: 'ai', taskId: String\(taskId\) \}/)
  assert.match(source, /name: 'repository', query: \{ section: 'pull-requests' \}/)
})
