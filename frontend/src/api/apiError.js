/**
 * 结构化的 API 错误:调用方按 status/code 分支,不再解析中文提示串。
 * traceId 透传自后端 X-Trace-Id 响应头,报障时可直接引用。
 */
export class ApiError extends Error {
  constructor(message, { status = 0, code = null, traceId = null } = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.traceId = traceId
  }
}
