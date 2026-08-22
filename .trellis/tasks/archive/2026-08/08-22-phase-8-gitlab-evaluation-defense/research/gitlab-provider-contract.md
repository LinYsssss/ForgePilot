# GitLab provider contract research

Date: 2026-08-22. Holdout data was not accessed during this research.

## Authoritative sources

- GitLab Merge Requests API: <https://docs.gitlab.com/api/merge_requests/>
- Source Markdown for the same API: <https://gitlab.com/gitlab-org/gitlab/-/raw/master/doc/api/merge_requests.md>
- GitLab webhook events: <https://gitlab.com/gitlab-org/gitlab/-/raw/master/doc/user/project/integrations/webhook_events.md>
- GitLab webhook authentication/delivery headers: <https://gitlab.com/gitlab-org/gitlab/-/raw/master/doc/user/project/integrations/webhooks.md>

These were read from the official GitLab documentation repository on 2026-08-22.

## Merge request reads

- One MR is addressed as `GET /projects/:id/merge_requests/:merge_request_iid`. `id` may be numeric; ForgePilot already stores the stable numeric project external ID, so it avoids namespace rename/URL encoding ambiguity.
- The response exposes `iid`, `title`, `source_branch`, `updated_at`, `author.id`, `author.username`, and `diff_refs.base_sha/head_sha/start_sha`.
- GitLab explicitly warns that `diff_refs` and `changes_count` are initially empty and populate asynchronously. ForgePilot must reject an incomplete authoritative response rather than persist blank SHAs.
- `diff_refs` corresponds to the latest diff version. Diff versions are available from `GET /projects/:id/merge_requests/:iid/versions`; each version carries a numeric `id`, base/head/start commit SHA, `state`, and other audit fields. The matching version ID is a suitable stable `sourceRevision`.
- Current changed files are listed by `GET /projects/:id/merge_requests/:iid/diffs`, with standard `page`/`per_page` pagination.
- Diff entries expose `old_path`, `new_path`, `new_file`, `deleted_file`, `renamed_file`, `diff`, and (GitLab 18.4+) `collapsed`/`too_large`. `too_large` means the diff is excluded and cannot be retrieved; `collapsed` means excluded from this response but available separately. ForgePilot's current model can preserve the path and explicit absence by storing `patch=null`; it must never turn either condition into an empty successful patch.
- Since the MR and paginated diff calls are separate, ForgePilot must detect a moving MR. Re-read the MR identity after gathering pages (or use an equivalently immutable version API) and never combine SHAs from one version with patches from another.

## Webhook routing and authentication

- MR deliveries use `X-Gitlab-Event: Merge Request Hook`; MR data lives under `object_attributes`, while `project` is the target project. Relevant routing fields are `project.id`, `project.web_url`, and `object_attributes.iid`.
- The payload is only a signal. GitLab documents that MR events can arrive with an empty `changes` field, so provider API reads remain authoritative.
- GitLab 19.0 introduced Standard Webhooks signing tokens and recommends them over the legacy secret token. Signed requests carry `webhook-id`, `webhook-timestamp`, and `webhook-signature`; signatures have `v1,<base64>` form and cover `{message_id}.{timestamp}.{body}` with HMAC-SHA256. Multiple space-separated signatures may appear.
- Replay resistance requires checking that `webhook-timestamp` is recent before processing. The exact acceptance window is receiver policy; ForgePilot will use a small documented window and an injected/testable clock.
- Legacy `X-Gitlab-Token` remains available and is sent as plaintext in a header. Backward-compatible receivers can prefer a present Standard signature and fall back only when signing headers are absent. A malformed/present signed form must not downgrade to Token authentication.
- Delivery retries keep a stable webhook/message ID. ForgePilot's database idempotency is based on the authoritative MR identity and snapshot, so duplicate delivery IDs need no seventeenth table.

## Error and security implications

- The per-repository `api_base` remains the provider endpoint and test seam; production code must not hardcode gitlab.com.
- API calls authenticate with `PRIVATE-TOKEN` (or an equivalent supported bearer form). ForgePilot's encrypted repository token stays write-only.
- Unknown repository identity, malformed routing input, stale/malformed signature, and bad token must share one 401 response and cause no outbound fetch or write.
- A verified event of another type is a 202 no-op.
- GitLab API 429 and transient 5xx failures should surface as retryable webhook failures, while malformed authoritative fields are explicit provider-payload failures. No partial MR snapshot may be saved.

## Implementation decision

Use the existing `scm_repository.encrypted_secret` for both modern signing-token and legacy secret-token verification. Prefer Standard HMAC verification when any Standard signing header is present; otherwise compare `X-Gitlab-Token` in constant time. This supports current GitLab and older/self-hosted deployments with no schema or runtime dependency change.

