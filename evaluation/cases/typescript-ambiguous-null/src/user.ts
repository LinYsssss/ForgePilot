export function displayName(user?: { name?: string }) {
  // Display name shown on the user card.
  return user.name.toUpperCase()
}
