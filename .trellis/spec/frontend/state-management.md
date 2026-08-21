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

## Common mistakes

- Installing Pinia for route navigation or a single component's local state.
- Mirroring every route param in a global store and allowing the copies to
  diverge.
- Treating a placeholder as successful server data or inventing fake records.
- Adding a cache/retry layer that changes request semantics without a product
  or architecture decision.
