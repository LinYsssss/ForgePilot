import { computed } from 'vue'
import { navItemsFor, resolveNavigation } from './inkNav.js'
import { nav } from '../../nav.js'
import { useBusy } from '../../composables/useBusy.js'
import { useSession } from '../../composables/useSession.js'
import { useWorkspace } from '../../composables/useWorkspace.js'

/**
 * 墨境侧栏导航的**唯一执行器**。
 *
 * 决策在 inkNav(纯函数、可测),执行在这里,两个墨境壳层(工作台与页框)共用同一份。
 * 此前工作台自带一张硬编码 navItems + 一个 onNavigate,与 inkNav 并列成了第二个真源:
 * 每迁一页都得记得同步两处,而漏同步只会在用户从工作台点那一项时才暴露——
 * inkNav 本来就是为了消灭这种形态而建的。
 *
 * @param activeKey 当前页 key;传函数可跟随响应式变化(页框的 activeKey 是 prop)
 */
export function useInkNavigation(activeKey) {
  const { activeProject } = useSession()
  const { run } = useBusy()
  const { refreshAll, goto, goTab, openAgentWorkspace, openProjectAiLogs } = useWorkspace()

  const navItems = computed(() => navItemsFor(!!activeProject.value))

  function onNavigate(key) {
    const current = typeof activeKey === 'function' ? activeKey() : activeKey
    const decision = resolveNavigation(key, current)
    if (decision.action === 'none') return
    if (decision.action === 'ink') {
      nav.push({ name: decision.routeName })
      // 与旧壳层 goTab 同语义:项目作用域页进入时按当前项目刷新一次
      if (decision.refresh && activeProject.value) run(refreshAll)
      return
    }
    if (decision.legacy === 'agent') return openAgentWorkspace()
    if (decision.legacy === 'aiLogs') return openProjectAiLogs()
    if (decision.legacy === 'goto') return goto(key)
    return goTab(key)
  }

  return { navItems, onNavigate }
}
