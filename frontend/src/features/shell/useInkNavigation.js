import { computed } from 'vue'
import { navItemsFor, resolveNavigation } from './inkNav.js'
import { nav } from '../../nav.js'
import { useSession } from '../../composables/useSession.js'
export function useInkNavigation(activeKey) {
  const { activeProject } = useSession()
  const navItems = computed(() => navItemsFor(!!activeProject.value))
  function onNavigate(key) {
    const current = typeof activeKey === 'function' ? activeKey() : activeKey
    const decision = resolveNavigation(key, current)
    if (decision.action === 'ink') nav.push({ name: decision.routeName })
  }
  return { navItems, onNavigate }
}
