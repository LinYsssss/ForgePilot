/**
 * 墨境侧栏导航模型(纯逻辑,便于 node 行为测试)。
 *
 * 逐页扩面(implement.md 步骤 8)是**渐进**的:某一页迁到墨境之前,点它仍要落回旧壳层。
 * 把「哪些页已经是墨境原生」收在这里单点声明,页面组件只管照着结果跳,
 * 避免每迁一页就去改散落各处的 if 分支——那正是上一轮 onNavigate 里已经开始堆积的形态。
 *
 * 迁移一页的动作因此收敛成两步:把它的 `ink: true` 打开、加一条 `/ink/<key>` 路由。
 */

/**
 * 侧栏条目。`ink: true` 表示该页已迁入墨境壳层,导航按 `routeName` 直接跳转;
 * 其余仍由旧壳层接管,保持既有跳转语义不变。
 *
 * 迁移一页**不改路径也不改路由名**,只把它的 `ink` 打开、把路由的 component 换成墨境页并加
 * `meta.shell`。这样既有的 goto/兜底重定向/外链锚点全都照常工作(步骤 8 要求「保持路由语义」)。
 */
export const INK_NAV = [
  { key: 'atelier', glyph: '墨', label: '墨境工作台', ink: true, routeName: 'inkAtelier', needsProject: false },
  { key: 'dashboard', glyph: '概', label: '总览', ink: true, routeName: 'dashboard', needsProject: false },
  { key: 'projects', glyph: '项', label: '项目', ink: true, routeName: 'projects', needsProject: false },
  { key: 'repository', glyph: '仓', label: '仓库', ink: false, needsProject: true },
  { key: 'pullRequests', glyph: '合', label: 'PR 工作流', ink: false, needsProject: true },
  { key: 'reviews', glyph: '审', label: '审查记录', ink: false, needsProject: true },
  { key: 'agent', glyph: '巡', label: 'Agent 审批(旧版)', ink: false, needsProject: true },
  { key: 'knowledge', glyph: '知', label: '知识库', ink: false, needsProject: true },
  { key: 'aiLogs', glyph: '录', label: 'AI 日志', ink: false, needsProject: true },
]

/** 已迁入墨境的页面 key;路由与测试都以此为单一真源。 */
export const INK_NATIVE_KEYS = INK_NAV.filter((item) => item.ink).map((item) => item.key)

/** 侧栏渲染用:按有无当前项目决定禁用态。 */
export function navItemsFor(hasProject) {
  return INK_NAV.map(({ key, glyph, label, needsProject }) => ({
    key,
    glyph,
    label,
    disabled: needsProject && !hasProject,
  }))
}

/**
 * 把一次点击解析成「该做什么」,不在这里执行——执行留给页面,便于测试。
 *
 * @returns {{action: 'none'|'ink'|'legacy', key: string, routeName?: string, legacy?: string}}
 *   - `none`:点的就是当前页,或 key 不存在;
 *   - `ink`:按 `routeName` 跳墨境页;
 *   - `legacy`:交给旧壳层,`legacy` 标明用哪条既有语义(agent/aiLogs 各有预加载动作)。
 */
export function resolveNavigation(key, currentKey) {
  if (key === currentKey) return { action: 'none', key }
  const item = INK_NAV.find((entry) => entry.key === key)
  if (!item) return { action: 'none', key }
  if (item.ink) return { action: 'ink', key, routeName: item.routeName }
  if (key === 'agent' || key === 'aiLogs') return { action: 'legacy', key, legacy: key }
  if (key === 'projects') return { action: 'legacy', key, legacy: 'goto' }
  return { action: 'legacy', key, legacy: 'goTab' }
}
