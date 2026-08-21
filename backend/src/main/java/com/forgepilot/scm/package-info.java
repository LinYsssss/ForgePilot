/**
 * Repository credentials, webhook intake, the authoritative pull request snapshot
 * and {@code REQ-<n>} parsing.
 *
 * <p>This feature does not decide anything about a Review. It publishes
 * {@link com.forgepilot.scm.PullRequestChanged} inside the transaction that
 * updated the pull request and has no compile time dependency on {@code review}.
 */
package com.forgepilot.scm;
