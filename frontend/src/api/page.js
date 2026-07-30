/**
 * 分页信封适配。冻结契约(docs/并行实施拆分方案.md 契约 1):
 * { items, page, size, totalElements, totalPages }
 *
 * 合流之前旧后端仍返回裸数组,合流之后变为信封;两种形状都接受,
 * 列表渲染不因两条线的合流顺序而坏。
 */
export function unwrapPage(data) {
  if (Array.isArray(data)) return data
  if (data && Array.isArray(data.items)) return data.items
  return []
}
