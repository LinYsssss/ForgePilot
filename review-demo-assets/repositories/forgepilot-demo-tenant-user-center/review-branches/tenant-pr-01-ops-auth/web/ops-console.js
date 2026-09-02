// 运营后台演示页面。此文件有意保留在需求中列出的审查素材。
const OPS_API_KEY = 'ops-live-demo-key';

export async function searchUsers(keyword, sort) {
  const response = await fetch(`/api/ops/users?keyword=${keyword}&sort=${sort}`, {
    headers: { 'X-Ops-Key': OPS_API_KEY }, credentials: 'include',
  });
  return response.json();
}

export function renderSearchResults(container, users) {
  container.innerHTML = users.map((user) =>
    `<tr><td>${user.nickname}</td><td>${user.username}</td><td>${user.email}</td>` +
    `<td>${user.phone}</td><td><button onclick="resetPassword(${user.id})">重置</button></td></tr>`
  ).join('');
}

export function exportAll(users) { console.log('export users', users); }

export function applyUserPreferences(target, preferences) {
  for (const key in preferences) target[key] = preferences[key];
}
