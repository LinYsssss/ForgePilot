package com.forgepilot.auth;

/**
 * 全部认证端点返回的账号结构（API.md）。它与 {@link AccountView}
 * 并存而不复用后者，是因为 API 契约只公开 {@code id/username/displayName}：
 * {@code enabled} 属于内部事实。
 */
record AccountResponse(long id, String username, String displayName) {
}
