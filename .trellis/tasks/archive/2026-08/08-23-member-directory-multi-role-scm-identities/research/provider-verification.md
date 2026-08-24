# Provider verification and one-time token handling

## Identity ownership proof

The client accepts `provider`, `apiBase`, a user-facing label/use type, and a one-time personal token. It first applies the existing outbound URL policy and normalizes the instance identity. It then calls the provider's authenticated-current-user endpoint:

| Provider | Request | Stable key | Display snapshot |
| --- | --- | --- | --- |
| GitHub | `GET {apiBase}/user`, `Authorization: Bearer …` | numeric `id` serialized as text | `login` |
| GitLab | `GET {apiBase}/user`, `PRIVATE-TOKEN: …` | numeric `id` serialized as text | `username` |

Only a successful authenticated response can create or refresh a verified identity. The application never accepts an external user id from the browser as proof.

## Project binding proof

Choosing a stored identity does not prove that a fresh credential still belongs to it or that it can read the project's repository. The binding request therefore takes another one-time token and performs, in order:

1. call the current-user endpoint and compare provider + normalized instance + numeric external id with the selected identity;
2. call the repository endpoint using the stable external repository id already stored by ForgePilot;
3. capture a non-secret permission summary and verification time;
4. discard the token, then create `ACTIVE` or `PENDING_APPROVAL` binding state.

Repository calls:

- GitHub: `GET {apiBase}/repositories/{externalRepositoryId}`; retain only the response's effective permissions summary.
- GitLab: `GET {apiBase}/projects/{urlEncodedExternalRepositoryId}`; retain only effective project/group access levels and visibility needed to explain the result.

HTTP 401/403/404 responses are normalized into non-enumerating validation errors. A successful anonymous/public lookup is not enough: the call is made with the verified credential, and the current-user check must have succeeded in the same request.

## Secret-safety rules

- The token exists only in the inbound request and provider-call stack. It is never stored in an entity, migration, audit row, response, metric tag, exception, or log field.
- Token-bearing request objects use a redacted `toString`; controllers/services never log request bodies or authorization headers.
- Provider clients construct headers immediately before the call and do not pass the token through repository-credential encryption or persistence APIs.
- Error messages exclude response request headers and provider payload fragments that might echo credentials.
- Tests use sentinel tokens and assert that persistence, serialized responses, and captured logs do not contain the sentinel.

## Deferred choice

OAuth Apps and durable personal grants are explicitly outside this version. They can later implement the same verification interface without changing the stable identity or project-binding keys.
