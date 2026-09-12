# State Management

The Phase 1 shell deliberately has no global store. Vue `ref`, `computed`,
and component props are sufficient for local presentation state, and the
router is the source of truth for URL state. Pinia and other state libraries
are not installed.

## State categories

- **Local UI state:** keep ephemeral values (expanded sections, input drafts,
  pending flags) in the owning component or a narrowly scoped composable.
- **URL state:** use route params and query parameters for state that must be
  linkable, reloadable, or represented by browser navigation. Read it through
  Vue Router rather than copying it into a second store.
- **Server state:** request data through `requestJson<T>` and keep the result,
  loading state, and error state with the view/feature that owns the request.
  Phase 1 has no server-backed business state or cache.
- **Shared application state:** only router configuration and immutable
  navigation constants are shared in the foundation (`routes.ts`).

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

There is no automatic cache, retry, polling, optimistic mutation, or query
invalidation in Phase 1. A feature that later needs those behaviors must state
their freshness, error, and cancellation semantics before adding an adapter.
Until then, call `requestJson<T>` explicitly, expose loading and failure states
in the UI, and preserve `HttpError` status/body information.

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

## Common mistakes

- Installing Pinia for route navigation or a single component's local state.
- Mirroring every route param in a global store and allowing the copies to
  diverge.
- Treating a placeholder as successful server data or inventing fake records.
- Adding a cache/retry layer that changes request semantics without a product
  or architecture decision.
