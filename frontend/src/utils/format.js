// 纯展示格式化工具(无状态、无副作用)。从 App.vue 抽出,便于复用与后续组件化。
export function shortCommit(id) { return id ? id.slice(0, 8) : '-' }
export function fmtTime(t) {
  if (!t) return '-'
  const d = new Date(t)
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
export function fmtDate(t) {
  if (!t) return '-'
  const d = new Date(t)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
