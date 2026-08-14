// 抽屉状态机(纯逻辑,无 DOM;InkShell 负责把决策落到 DOM/焦点上)。
// 冻结抽屉合同(ui-design.md §11 / D-007):
//   - 断点:≤767 案卷索引转抽屉;≤1279 朱批栏转抽屉;
//   - 同一时刻至多一个抽屉打开;
//   - 关闭态 inert + aria-hidden(移出键盘序);
//   - 打开:焦点进关闭按钮;关闭:焦点归还触发器;
//   - Escape 优先关朱批、再关案卷;抽屉内 Tab 循环。

export const MOBILE_LAYOUT_QUERY = '(max-width: 767px)'
export const RAIL_DRAWER_QUERY = '(max-width: 1279px)'

export function createDrawerController(initialLayout = {}) {
  let open = null // null | 'nav' | 'rail'
  const layout = { mobile: false, railDrawer: false }
  Object.assign(layout, sanitize(initialLayout))

  function sanitize(next) {
    const out = {}
    if ('mobile' in next) out.mobile = !!next.mobile
    if ('railDrawer' in next) out.railDrawer = !!next.railDrawer
    return out
  }

  function snapshot() {
    return {
      open,
      mobile: layout.mobile,
      railDrawer: layout.railDrawer,
      anyOpen: open !== null,
      // 关闭态才移出可达性树;非抽屉布局下侧栏是常规列,不设 inert
      navHidden: layout.mobile && open !== 'nav',
      railHidden: layout.railDrawer && open !== 'rail',
    }
  }

  /**
   * 打开抽屉(互斥:另一侧自动关闭)。
   * @param {'nav'|'rail'} type
   * @returns {'nav-close'|'rail-close'|null} 打开后应聚焦的目标
   */
  function openDrawer(type) {
    if (type !== 'nav' && type !== 'rail') return null
    open = type
    return type === 'nav' ? 'nav-close' : 'rail-close'
  }

  /**
   * 关闭当前抽屉。
   * @returns {'nav-toggle'|'rail-toggle'|null} 应归还焦点的触发器(仍处抽屉布局时)
   */
  function closeDrawer() {
    const closed = open
    open = null
    if (closed === 'nav' && layout.mobile) return 'nav-toggle'
    if (closed === 'rail' && layout.railDrawer) return 'rail-toggle'
    return null
  }

  /**
   * 布局变化(离开抽屉断点时,已打开的抽屉自动归位关闭)。
   * @param {{mobile?: boolean, railDrawer?: boolean}} next
   * @returns {boolean} 是否因布局变化关闭了抽屉
   */
  function setLayout(next) {
    Object.assign(layout, sanitize(next))
    if (open === 'nav' && !layout.mobile) {
      open = null
      return true
    }
    if (open === 'rail' && !layout.railDrawer) {
      open = null
      return true
    }
    return false
  }

  /** Escape 关闭优先级:朱批 > 案卷(与已批准原型一致) */
  function escapeTarget() {
    if (open === 'rail') return 'rail'
    if (open === 'nav') return 'nav'
    return null
  }

  return { snapshot, openDrawer, closeDrawer, setLayout, escapeTarget }
}

/**
 * 抽屉内 Tab 循环:返回应强制聚焦的下标;null 表示交给浏览器默认顺序。
 * @param {number} count 可聚焦元素个数
 * @param {number} activeIndex 当前焦点下标(不在列表内为 -1)
 * @param {boolean} shiftKey
 */
export function nextTrapIndex(count, activeIndex, shiftKey) {
  if (count <= 0) return null
  if (activeIndex === -1) return shiftKey ? count - 1 : 0
  if (shiftKey && activeIndex === 0) return count - 1
  if (!shiftKey && activeIndex === count - 1) return 0
  return null
}
