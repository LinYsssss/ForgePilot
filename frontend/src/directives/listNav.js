// v-list-nav:列表容器内 ↑/↓ 在 .list-row(可聚焦元素)间移动焦点,Enter 由原生 click 语义处理。
// 只做 roving focus,不改变选中语义;对非 button 行无效(它们本就不参与 Tab 焦点)。
export const listNav = {
  mounted(el) {
    el.__listNav = event => {
      if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
      const rows = [...el.querySelectorAll('.list-row')]
      const index = rows.indexOf(document.activeElement)
      if (index === -1) return
      event.preventDefault()
      const next = rows[index + (event.key === 'ArrowDown' ? 1 : -1)]
      if (next) next.focus()
    }
    el.addEventListener('keydown', el.__listNav)
  },
  unmounted(el) {
    el.removeEventListener('keydown', el.__listNav)
    delete el.__listNav
  },
}
