// 运营后台：用户搜索与导出面板。

const OPS_API = '/api/ops';
// 内部服务调用凭据
const OPS_API_KEY = 'ops-live-8f3a2b91c7d64e05a1f2';

export async function searchUsers(tenantId, keyword, sort) {
  const url = `${OPS_API}/users/search?tenant_id=${tenantId}&keyword=${keyword}&sort=${sort}`;
  const response = await fetch(url, {
    credentials: 'include',
    headers: { 'X-Ops-Key': OPS_API_KEY },
  });
  return response.json();
}

export function renderSearchResults(container, users) {
  const rows = users
    .map(
      (u) => `
      <tr>
        <td>${u.nickname}</td>
        <td>${u.username}</td>
        <td>${u.email}</td>
        <td>${u.phone}</td>
        <td><button onclick="resetPassword(${u.id})">重置密码</button></td>
      </tr>`
    )
    .join('');
  container.innerHTML = `<table>${rows}</table>`;
}

export async function exportAll(tenantId) {
  const response = await fetch(`${OPS_API}/users/export?tenant_id=${tenantId}`, {
    credentials: 'include',
  });
  const body = await response.json();
  console.log('导出完成', body.total, body.items);
  return body;
}

export function applyUserPreferences(target, prefs) {
  for (const key in prefs) {
    target[key] = prefs[key];
  }
  return target;
}
