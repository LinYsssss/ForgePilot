package com.forgepilot.auth;

/**
 * The account shape every auth endpoint returns (api-contract.md 1). It exists
 * next to {@link AccountView} rather than reusing it because the API contract is
 * exactly {@code {id, username}}: {@code enabled} is an internal fact.
 */
record AccountResponse(long id, String username) {
}
