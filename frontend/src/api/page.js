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

/**
 * 读取分页信封的元数据,同时兼容合流前的裸数组。
 * 列表调用方应先用 unwrapPage 取 items,再用本函数取得可选分页信息。
 */
export function pageMeta(data, { page = 0, size = 20 } = {}) {
  const items = unwrapPage(data)
  if (Array.isArray(data)) {
    return {
      page,
      size,
      totalElements: items.length,
      totalPages: items.length ? 1 : 0,
    }
  }
  const totalElements = Number.isFinite(Number(data?.totalElements))
    ? Number(data.totalElements)
    : items.length
  const safeSize = Number.isFinite(Number(data?.size)) && Number(data.size) > 0
    ? Number(data.size)
    : size
  const totalPages = Number.isFinite(Number(data?.totalPages))
    ? Number(data.totalPages)
    : (totalElements ? Math.ceil(totalElements / safeSize) : 0)
  return {
    page: Number.isFinite(Number(data?.page)) ? Number(data.page) : page,
    size: safeSize,
    totalElements,
    totalPages,
  }
}
