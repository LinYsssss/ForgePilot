export function displayName(user?: { name?: string }) {
  // Ambiguous: callers may guarantee user, so verifier evidence is required before blocking.
  return user.name.toUpperCase()
}
