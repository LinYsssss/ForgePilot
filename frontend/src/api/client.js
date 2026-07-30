import { ApiError } from './apiError'

export { ApiError }

export const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api'

/**
 * CSRF 状态:启动时经 GET /auth/csrf 引导。开关关闭(默认)时保持 enabled=false,
 * 写请求不附加任何头;开启后令牌始终从 Cookie 现读——登录/退出的轮换天然生效,
 * 前端不缓存令牌值,也绝不落入 Web Storage。
 */
const csrf = { enabled: false, cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

export async function initCsrf() {
  try {
    const data = await api('/auth/csrf')
    csrf.enabled = !!data?.enabled
    if (data?.cookieName) csrf.cookieName = data.cookieName
    if (data?.headerName) csrf.headerName = data.headerName
  } catch {
    csrf.enabled = false // 引导失败按关闭处理:老后端没有该端点,也不该因此瘫痪
  }
}

function readCookie(name) {
  const match = document.cookie.match(new RegExp(`(?:^|;\\s*)${name}=([^;]*)`))
  return match ? decodeURIComponent(match[1]) : ''
}

/**
 * 会话失效(401)的全局出口:注册一次,由 App 负责失效会话并提示。
 * 集中处理是为了并发请求同时 401 时只产生一次登出与一条提示,而不是每个调用各弹各的。
 */
let onUnauthorized = null
export function setUnauthorizedHandler(handler) { onUnauthorized = handler }

export async function api(path, options = {}) {
  const headers = {
    ...(options.headers || {})
  }
  if (!(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
  }
  const method = (options.method || 'GET').toUpperCase()
  if (csrf.enabled && !SAFE_METHODS.has(method)) {
    const token = readCookie(csrf.cookieName)
    if (token) headers[csrf.headerName] = token
  }
  let response
  try {
    response = await fetch(`${API_BASE}${path}`, {
      ...options,
      headers,
      credentials: 'include'
    })
  } catch (networkError) {
    throw new ApiError('网络错误，请确认后端服务已启动', { status: 0 })
  }
  // 401 的响应体是空的(HttpStatusEntryPoint),必须在解析 JSON 之前拦下。
  if (response.status === 401) {
    if (onUnauthorized) onUnauthorized()
    throw new ApiError('登录已过期，请重新登录', { status: 401 })
  }
  if (response.status === 204) {
    return null
  }
  const traceId = response.headers.get('X-Trace-Id')
  const json = await response.json().catch(() => null)
  if (!response.ok || !json || json.code !== 0) {
    const base = json?.message || (response.ok ? '响应解析失败' : `请求失败: ${response.status}`)
    throw new ApiError(traceId ? `${base}（trace ${traceId}）` : base, {
      status: response.status,
      code: json?.code ?? null,
      traceId
    })
  }
  return json.data
}

/**
 * 下载类接口:后端直接返回文件流(不包 {code,message,data}),故不能走 api()。
 * 返回 blob 与响应头里的建议文件名。
 */
export async function apiDownload(path) {
  let response
  try {
    response = await fetch(`${API_BASE}${path}`, { credentials: 'include' })
  } catch (networkError) {
    throw new ApiError('网络错误，请确认后端服务已启动', { status: 0 })
  }
  if (response.status === 401) {
    if (onUnauthorized) onUnauthorized()
    throw new ApiError('登录已过期，请重新登录', { status: 401 })
  }
  if (!response.ok) {
    const json = await response.json().catch(() => null)
    throw new ApiError(json?.message || `下载失败: ${response.status}`, {
      status: response.status,
      code: json?.code ?? null
    })
  }
  const disposition = response.headers.get('Content-Disposition') || ''
  const match = /filename="?([^";]+)"?/.exec(disposition)
  return { blob: await response.blob(), filename: match ? match[1] : 'reposage-download' }
}
