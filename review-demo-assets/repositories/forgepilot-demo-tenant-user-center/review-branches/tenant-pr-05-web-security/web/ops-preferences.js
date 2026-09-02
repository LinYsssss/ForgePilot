export function render(container, user) {
  container.innerHTML = `<p>${user.nickname}: ${user.email}</p>`;
}
export function merge(target, source) { for (const key in source) target[key] = source[key]; }
