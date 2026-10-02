# State Management

The application deliberately has no external state-store library. Vue `ref`,
`computed`, feature-owned session state and component props handle mutable state;
the router is the source of truth for URL state. Pinia is not installed.

## State categories

- **Local UI state:** keep ephemeral values (expanded sections, input drafts,
  pending flags) in the owning component or a narrowly scoped composable.
- **URL state:** use route params and query parameters for state that must be
  linkable, reloadable, or represented by browser navigation. Read it through
  Vue Router rather than copying it into a second store.
- **Server state:** request data through `requestJson<T>` and keep the result,
  loading state, and error state with the view/feature that owns the request.
  Requirements, reviews, project membership and knowledge records come from the
  backend; no shared client cache replaces those authoritative records.
- **Shared application state:** navigation constants live in `routes.ts`.
  `features/auth/session.ts` owns the shared account ref, exposes a readonly
  computed value through `useSession`, and updates it only through auth actions.

Derived values should be `computed` from one authoritative source. Do not
duplicate route params, server records, or status fields merely to make a
template shorter.

## Promotion to shared state

Do not promote state to a global store until two or more authorized features
need the same mutable state, its ownership and update rules are explicit, and
URL/local ownership is insufficient. Such a change requires a design review
and a documented contract; it must not be introduced as a convenience in a
placeholder view.

Cross-feature state must preserve project and permission boundaries. A store
must not become a hidden way to bypass the backend authorization or transaction
contract.

## Server state

There is no automatic query cache or query invalidation layer. Review execution
and knowledge ingestion use the shared `useFinitePolling`: it schedules the next
read only after the previous read
settles, runs only while the displayed record is PENDING/RUNNING, pauses while
the page is hidden, and stops at terminal state or unmount. Three consecutive
failures stop automatic reads and expose a manual retry. Feature callbacks still
call `requestJson<T>` explicitly, preserve `HttpError` information, and apply a
response only when its project/route generation still owns the page.

## Async operations on reused detail pages

`ReviewDetailPage.vue` identifies a page visit by project ID, review ID, and a
monotonic load generation. Route IDs alone are insufficient: navigating A → B → A
must invalidate operations started during the first visit to A. Increment the
generation on reload and unmount.

Every asynchronous continuation belongs to the visit that started it. Capture
the IDs and generation before awaiting, and check them before changing records,
comments, selections, error messages, or pending flags. This includes `catch`
and `finally`, as well as every follow-up read after a successful mutation.
Reset pending and selection state when the page changes so old work cannot
disable actions on the new page.

```ts
const loadedReview = await getReview(ids.projectId, ids.reviewId);
if (!isCurrentPage(ids, token)) return;
detail.value = loadedReview;
```

Do not assign `detail.value = await getReview(...)` without an intervening
ownership check. Capture the PR ID at operation start instead of consulting
`pullRequest.value` after an await. Before sending a write, verify that the
displayed project, review, and PR match the route (`displayedTarget()`); disable
decisions while the page or its requirement association is still refreshing.

`tests/reviewNavigation.spec.ts` delays mutation and refresh responses, navigates
to another review/project or revisits the original review, and checks both the
rendered identity and outgoing decision target. It also proves old errors and
`finally` callbacks cannot clear a new visit's input or pending operation.
Polling refreshes only the result record/list. It must not call a detail page's
initial `load()` because that function resets comments, filters, selected evidence,
attachment inputs, and other local edits.

## Scenario: one-shot advice for mutable drafts

### 1. Scope / Trigger

A generated suggestion must stay attached to the saved content it describes,
not whichever form happens to be visible when a slow response arrives.

### 2. Signatures

`generateGuidance(projectId, requirementId)` calls `POST .../guidance`.
`RequirementDetailPage.vue` owns `draftContent`, `invalidateGuidance`,
`runGuidance`, and the shared copy/download Markdown formatter.

### 3. Contracts

Keep checklist/rules/risks as string arrays. The additional summary/questions/
guidanceVersion fields may be absent with an older service; absence is not an
empty assessment. Knowledge similarity is retrieval relevance, not correctness.
The server reads saved content only and stores no suggestion history.

### 4. Validation & Error Matrix

| Situation | Required behavior |
|---|---|
| Unsaved draft or unpublished edits | Disable generation, explain why; never save automatically |
| Same revisionId after a DRAFT save | Invalidate both old result and in-flight request |
| Old success/error/finally arrives | Do not change the newer request's result/error/pending state |
| Response names a different revision | Discard it and ask the user to refresh |
| Regeneration fails without content change | Keep and identify the previous valid suggestion |
| Clipboard absent or denied | Report failure and offer Markdown download |

### 5. Good / Base / Bad Cases

A fresh unchanged form can generate even when optional prose is null or empty.
Saving a DRAFT must invalidate by request generation, not just revisionId.
Do not render missing questions as “no questions”, or silently keep advice after
known saved content changed.

### 6. Tests Required

`frontend/tests/requirement.spec.ts` holds an old answer across a same-id draft
save and a newer generation, checks the new pending flag, legacy fields and
failure retention, and compares clipboard text with the downloaded Blob.
`ImplementationGuidanceTest` owns response structure, roles and gateway calls.

### 7. Wrong vs Correct

Wrong: `guidance.value = await generateGuidance(...)` followed by an unconditional
`pending = false`. Correct: capture the request generation, guard success/catch/
finally, and advance it on save, reload and unmount. Use one formatter for both
copy and download so neither loses the revision or reference context.

## Common mistakes

- Installing Pinia for route navigation or a single component's local state.
- Mirroring every route param in a global store and allowing the copies to
  diverge.
- Treating a placeholder as successful server data or inventing fake records.
- Adding a cache/retry layer that changes request semantics without a product
  or architecture decision.
