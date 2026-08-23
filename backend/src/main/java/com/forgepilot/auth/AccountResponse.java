package com.forgepilot.auth;

/**
 * 全部认证端点返回的账号结构（api-contract.md 1）。它与 {@link AccountView}
 * 并存而不复用后者，是因为 API 契约恰好只有 {@code {id, username}}：
 * {@code enabled} 属于内部事实。
 */
record AccountResponse(long id, String username) {
}
