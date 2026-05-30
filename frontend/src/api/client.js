const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api'

export function getToken() {
  return localStorage.getItem('token') || ''
}

export function setToken(token) {
  localStorage.setItem('token', token)
}

export function clearToken() {
  localStorage.removeItem('token')
}

export async function api(path, options = {}) {
  const headers = {
    ...(options.headers || {})
  }
  if (!(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
  }
  const token = getToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  let response
  try {
    response = await fetch(`${API_BASE}${path}`, { ...options, headers })
  } catch (networkError) {
    throw new Error('网络错误，请确认后端服务已启动')
  }
  if (response.status === 401 || response.status === 403) {
    clearToken()
  }
  const json = await response.json().catch(() => ({ code: 500, message: '响应解析失败' }))
  if (!response.ok || json.code !== 0) {
    throw new Error(json.message || `请求失败: ${response.status}`)
  }
  return json.data
}
