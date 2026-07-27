const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api'

export async function api(path, options = {}) {
  const headers = {
    ...(options.headers || {})
  }
  if (!(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
  }
  let response
  try {
    response = await fetch(`${API_BASE}${path}`, {
      ...options,
      headers,
      credentials: 'include'
    })
  } catch (networkError) {
    throw new Error('网络错误，请确认后端服务已启动')
  }
  if (response.status === 204) {
    return null
  }
  const traceId = response.headers.get('X-Trace-Id')
  const json = await response.json().catch(() => ({ code: 500, message: '响应解析失败' }))
  if (!response.ok || json.code !== 0) {
    const base = json.message || `请求失败: ${response.status}`
    throw new Error(traceId ? `${base}（trace ${traceId}）` : base)
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
    throw new Error('网络错误，请确认后端服务已启动')
  }
  if (!response.ok) {
    const json = await response.json().catch(() => null)
    throw new Error(json?.message || `下载失败: ${response.status}`)
  }
  const disposition = response.headers.get('Content-Disposition') || ''
  const match = /filename="?([^";]+)"?/.exec(disposition)
  return { blob: await response.blob(), filename: match ? match[1] : 'reposage-download' }
}
