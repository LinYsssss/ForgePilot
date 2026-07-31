// 管理后台用户列表渲染。
// 用户可控内容一律 textContent，禁止 innerHTML。见 docs/api-contract.md 第 4 节。

const API_BASE = '/api';

export async function fetchUsers(page = 0, size = 20) {
  const response = await fetch(`${API_BASE}/users?page=${page}&size=${size}`, {
    credentials: 'include',
  });
  if (!response.ok) {
    throw new Error(`加载用户失败: ${response.status}`);
  }
  const body = await response.json();
  return body.data;
}

export function renderUserRow(user) {
  const row = document.createElement('tr');

  const nickname = document.createElement('td');
  // 昵称是用户可控的，只能作为文本插入
  nickname.textContent = user.nickname ?? '';
  row.appendChild(nickname);

  const username = document.createElement('td');
  username.textContent = user.username;
  row.appendChild(username);

  const role = document.createElement('td');
  role.textContent = user.role;
  row.appendChild(role);

  return row;
}

export function renderUserTable(container, users) {
  container.replaceChildren();
  const table = document.createElement('table');
  users.forEach((user) => table.appendChild(renderUserRow(user)));
  container.appendChild(table);
}
