# ForgePilot 全功能测试手册

本手册用于在一个**空库**上从零走完 ForgePilot 的全部功能，覆盖认证、项目与成员、仓库接入、SCM 身份、项目知识、需求与验收条件、推送触发 PR、审查执行、Finding 生命周期、审查决策、通知与归档。

每个测试项给出**操作、预期结果、验证方式**三段。验证方式尽量给可直接执行的命令，避免只靠肉眼看界面。

> 手册假设部署形态为 `docs/v2/DEFENSE-GUIDE.md` 描述的容器部署，数据库容器名 `fp-demo-postgres-1`，后端 `fp-demo-backend-1`。若你的部署命名不同，替换即可。

---

## 0. 前置条件

### 0.1 环境自检

```bash
docker ps --format '{{.Names}}\t{{.Status}}' | grep fp-demo
curl -s http://127.0.0.1:18080/actuator/health
```

三个容器 `healthy`、健康检查返回 `{"status":"UP"}` 才继续。

### 0.2 确认是空库

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select 'project' t, count(*) from project
union all select 'requirement', count(*) from requirement
union all select 'pull_request', count(*) from pull_request
union all select 'review', count(*) from review
union all select 'knowledge_document', count(*) from knowledge_document
union all select 'scm_repository', count(*) from scm_repository
union all select 'user_account', count(*) from user_account;"
```

除 `user_account` 外都应为 0。

### 0.3 测试账号

本手册需要多个账号来验证角色分工。账号清单与统一密码保存在**本机文件**中（不入库、不入仓）。至少需要：

| 用途 | 数量 | 说明 |
|---|---|---|
| LEADER | 1 | 你自己的账号 |
| DEVELOPER | ≥2 | 验证「仅本人 PR」类权限需要两个不同开发者 |
| REVIEWER | ≥1 | 验证 Finding 确认与终局决策 |

账号可通过登录页自助注册（`POST /api/auth/register` 是公开端点）。**注意该端点限流：同一来源 10 分钟内最多 5 次**，批量注册会被 429 拒绝。

### 0.4 GitHub 凭据

空库意味着仓库接入需要重新配置，你需要准备：

1. **GitHub Personal Access Token**，勾选 `repo` 权限（私有仓必需）。在 <https://github.com/settings/tokens> 生成。
2. **Webhook Secret**：一个随机串，两侧必须一致。

   ```bash
   openssl rand -hex 32
   ```

   它的作用是让 ForgePilot 能区分「真的是 GitHub 发来的」和「有人伪造」。GitHub 用它对请求体做 HMAC-SHA256 放进 `X-Hub-Signature-256`，ForgePilot 用自己存的那份重算比对，不一致返回 401。

   **两侧都读不回来**（ForgePilot 加密只写，GitHub 显示 `********`），所以只能重设新值、两边同时写。生成后先复制保存再操作。

### 0.5 本地准备测试仓库

```bash
mkdir -p ~/fp-test && cd ~/fp-test
git clone https://github.com/<owner>/<repo-a>.git
git clone https://github.com/<owner>/<repo-b>.git
git config --global user.name  "<你的名字>"
git config --global user.email "<你的邮箱>"
```

**PR 作者的邮箱/用户名要与稍后绑定的 SCM 身份一致**，否则「仅本人 PR」类权限判定不会命中（判定按 Provider + 实例 + 稳定外部用户 ID，不按用户名）。

---

## 1. 测试总表

按顺序执行，后面的项依赖前面的产物。

| 组 | 项 | 功能 | 依赖 |
|---|---|---|---|
| A | A1–A6 | 认证与账户 | — |
| B | B1–B8 | 项目与成员 | A |
| C | C1–C7 | SCM 身份与仓库接入 | B |
| D | D1–D5 | 项目知识 | B |
| E | E1–E10 | 需求与验收条件 | B、D |
| F | F1–F7 | 推送 → PR → Webhook → Review | C、E |
| G | G1–G8 | Finding 生命周期 | F |
| H | H1–H6 | 审查决策与需求收尾 | G |
| I | I1–I3 | 通知 | B |
| J | J1–J5 | 归档、删除与隔离 | 全部 |
| K | K1–K9 | 负向与边界 | 全部 |

---

## 2. A 组：认证与账户

### A1 自助注册

**操作**：登录页 → 注册，填用户名、显示名、密码（密码 ≥8 字符、≤72 字节）。

**预期**：201，返回 `{id, username, displayName}`，自动或手动登录后可进入工作台。

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot \
  -c "select id,username,display_name,enabled,session_version from user_account order by id;"
```

`password_hash` 应为 bcrypt 格式（`$2a$10$` 开头），**不得出现明文**。

### A2 注册限流

**操作**：连续注册 6 个账号。

**预期**：第 6 次返回 **429**，而不是 500 或成功。10 分钟后恢复。

> 这一条限流只针对未认证的登录与注册两个写端点，已认证成员的批量添加成员等操作不受影响。

### A3 用户名唯一

**操作**：用已存在的用户名再注册一次。

**预期**：被拒绝，且**错误信息不泄露该用户名是否存在**的额外细节。

### A4 登录与会话

**操作**：用正确密码登录，然后访问 `/api/auth/me`。

**预期**：登录 200，`/me` 返回当前账号。响应带 `XSRF-TOKEN` cookie。

**验证**：

```bash
rm -f /tmp/cj.txt
curl -s -c /tmp/cj.txt -o /dev/null http://127.0.0.1:18080/api/auth/me
T=$(awk '/XSRF-TOKEN/{print $NF}' /tmp/cj.txt)
curl -s -b /tmp/cj.txt -c /tmp/cj.txt -o /dev/null -w 'login=%{http_code}\n' \
  -X POST http://127.0.0.1:18080/api/auth/login -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=<账号>" --data-urlencode "password=<密码>"
curl -s -b /tmp/cj.txt http://127.0.0.1:18080/api/auth/me; echo
```

### A5 CSRF 保护

**操作**：不带 `X-XSRF-TOKEN` 头直接 POST 任一写端点。

**预期**：**403**，且不产生任何副作用。

### A6 改密与会话版本失效

**操作**：账户页（`/account`）修改密码。在另一个浏览器/隐私窗口保持该账号的旧会话。

**预期**：改密成功后，**旧会话立即失效**，旧会话的请求返回 401，且响应体与「压根没有会话」完全相同。

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot \
  -c "select username, session_version from user_account order by id;"
```

`session_version` 应递增。

### A7 修改显示名

**操作**：账户页修改显示名。

**预期**：成员目录、需求指派人、Finding 处理人等所有展示位同步更新。

---

## 3. B 组：项目与成员

### B1 创建项目

**操作**：**项目** 页 → 新建项目，填名称。

**预期**：创建成功，创建者自动成为该项目**唯一 LEADER**。

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select p.id, p.name, p.status, u.username as creator,
       (select string_agg(role,'+' order by role) from project_member_role r
         where r.project_id=p.id and r.user_id=u.id) as roles
from project p join user_account u on u.id=p.created_by order by p.id;"
```

建议建 **3 个项目**，分别对应三个测试仓库。

### B2 成员目录搜索

**操作**：项目 → 成员 → 候选人搜索，分别用**显示名**、**用户名**、**平台 ID** 搜索。

**预期**：三种都能搜到。已是成员的人不应重复出现在候选列表里。

**对应端点**：`GET /api/projects/{projectId}/members/candidates`

### B3 原子批量添加成员

**操作**：一次勾选多个候选人（含 5 个开发、5 个评审），批量添加。

**预期**：一次性全部成功。

**边界**：
- 一次超过 **50** 人应被拒绝；
- 搜索关键字**少于 2 个字符**应被拒绝；
- 任一行**未选角色**应被拒绝，且**整批不落库**（原子性）；
- 失败提示应能定位到具体是第几行。

**验证**：失败后立即查库，`project_member` 不应有部分写入。

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select pm.project_id, u.username, u.display_name,
       string_agg(r.role,'+' order by r.role) as roles
from project_member pm join user_account u on u.id=pm.user_id
left join project_member_role r on r.project_id=pm.project_id and r.user_id=pm.user_id
group by pm.project_id, u.username, u.display_name
order by pm.project_id, u.username;"
```

### B4 成员多角色与能力并集

**操作**：给同一个成员同时勾选 DEVELOPER 与 REVIEWER。

**预期**：该成员同时具备两种角色的能力（并集）。

**对应端点**：`PATCH /api/projects/{projectId}/members/{userId}/roles`

> 这一条很重要：如果你只有一个人手，给自己同时勾上三个角色就能单人走完全流程。但**要真正验证权限边界，必须用不同账号**，见 K 组。

### B5 唯一 LEADER 约束

**操作**：尝试把第二个成员也设为 LEADER。

**预期**：被拒绝。每个项目**恰有一个** LEADER。

### B6 Leader 转移

**操作**：项目 → 成员 → Leader 转移，指定新 LEADER。

**预期**：原 LEADER 失去 LEADER 角色，新成员获得，项目仍只有一个 LEADER。

**对应端点**：`POST /api/projects/{projectId}/members/leader-transfer`

> 转移后原 LEADER 若还需管理权限，需要重新授予。**测试完记得转回来**，否则后续步骤会因权限不足卡住。

### B7 成员移除与活权限撤销

**操作**：先让某成员成为**某需求的指派人**、**某 Finding 的处理人**、并**绑定 SCM 身份**，然后移除该成员。

**预期**：移除成功，且该成员在三处的活权限被同步撤销。

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select 'requirement.assignee' src, count(*) from requirement where assignee_id = <userId>
union all select 'finding.assignee', count(*) from finding where assignee_id = <userId>
union all select 'scm_binding', count(*) from project_member_scm_binding where user_id = <userId>;"
```

三项都应为 0。**若移除时报外键错误（23503），说明有一处撤销没生效**——这不是 bug 掩盖，而是外键在证明撤销的完整性。

### B8 跨项目不可见

**操作**：用只属于项目 A 的账号访问项目 B 的任意资源。

**预期**：404 或 403，**不得**返回内容，也不得通过错误信息区分「不存在」与「无权限」以外的额外信息。

---

## 4. C 组：SCM 身份与仓库接入

### C1 添加并验证自己的 SCM 身份

**操作**：账户页 → SCM 身份 → 新增，用一次性 Token 向 Provider 验证。

**预期**：验证成功后身份状态为 `VERIFIED`，记录 provider、实例、外部用户 ID、外部用户名、标签、用途类型。

**关键**：**一次性 Token 不落库**。

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id,user_id,provider,instance_identity,external_user_id,external_username,
       label,usage_type,verification_status,verification_method,verified_at
from scm_identity order by id;"
```

表中**没有任何 token 列**，这本身就是证明。

**对应端点**：`POST /api/scm/identities/verify`

### C2 身份标注与撤销

**操作**：修改身份的标签与用途类型（`WORK` / `PERSONAL` / `CLIENT` / `OTHER`），然后撤销一个身份。

**预期**：修改生效；撤销后状态变为 `REVOKED`，且依赖该身份的项目绑定同步失效。

**对应端点**：`PATCH /api/scm/identities/{identityId}`、`DELETE /api/scm/identities/{identityId}`

### C3 为项目选择兼容身份

**操作**：项目内选择自己的一个**兼容**（同 provider + 同实例）身份进行绑定。

**预期**：绑定成功。不兼容的身份不应出现在可选项里。

**对应端点**：`GET /api/projects/{projectId}/scm/binding-options`、`POST /api/projects/{projectId}/scm/bindings`

### C4 严格审批模式

**操作**：LEADER 开启该项目的「身份绑定需审批」，然后让另一个成员提交绑定。

**预期**：绑定进入待审状态，LEADER 可**批准 / 拒绝 / 撤销**。未批准前该成员不享有「本人 PR」相关权限。

**对应端点**：`POST /api/projects/{projectId}/scm/bindings/{bindingId}/approve|reject|revoke`

### C5 注册仓库

**操作**：**仓库接入**（`/repositories`）→ 选中项目 → 配置 GitHub：

| 字段 | 值 |
|---|---|
| Provider | GITHUB |
| API 地址 | `https://api.github.com` |
| 外部仓库 ID | 见下方获取方式 |
| Token | 你的 PAT |
| Webhook Secret | 0.4 生成的随机串 |

获取外部仓库 ID：

```bash
gh api repos/<owner>/<repo> --jq .id
```

**预期**：注册成功。**Token 与 Secret 都是只写的**，页面不回显。

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id,project_id,provider,instance_identity,external_id,api_base,
       identity_approval_required,
       length(encrypted_token) as tok_len, length(encrypted_secret) as sec_len
from scm_repository order by id;"
```

两个长度字段非零、且**内容是密文**（不是你输入的原值）。

> **一个项目一个仓库**是 MVP 约束。

### C6 在 GitHub 侧创建 Webhook

```bash
SECRET='<与 ForgePilot 中填入的完全一致>'
gh api -X POST repos/<owner>/<repo>/hooks \
  -f name=web -F active=true -f 'events[]=pull_request' \
  -f 'config[url]=https://<你的域名>/api/scm/github/webhook' \
  -f 'config[content_type]=json' \
  -f 'config[insecure_ssl]=0' \
  -f "config[secret]=$SECRET"
```

**立即验证签名是否对上**：

```bash
HOOK=$(gh api repos/<owner>/<repo>/hooks --jq '.[0].id')
gh api -X POST repos/<owner>/<repo>/hooks/$HOOK/pings
sleep 4
gh api repos/<owner>/<repo>/hooks/$HOOK/deliveries \
  --jq '.[0] | "\(.event) -> \(.status_code)"'
```

**必须看到 `202`。** 收到 `401` 就是两侧 secret 不一致——这是最常见的坑，务必在继续之前解决。

> 为什么 ping 返回 202 就能证明 secret 对了：webhook 入口先验签，验签通过后发现是 ping 这种「本部署无事可做」的事件就直接返回 202。能走到 202 说明签名已经过了。

### C7 仓库身份冻结

**操作**：在该仓库**已经产生过 PR 之后**，尝试修改它的 provider / 实例 / 外部仓库 ID。

**预期**：**409 冲突**，提示该仓库已有 PR、身份不可再变。

**可以改的**：凭据与 Webhook Secret 随时可轮换；API 地址只能改成归一化后仍指向同一实例的地址。

> 反过来说：**仓库还没有 PR 时是可以改绑的**。要调整绑定关系就趁这个窗口。

---

## 5. D 组：项目知识

### D1 上传项目知识

**操作**：**项目知识**（`/knowledge`）→ 选中项目 → 上传或粘贴文档。

**预期**：文档状态经 `PENDING → READY`。

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id,project_id,source_type,title,status,failure_reason,length(text) as len
from knowledge_document order by id;"
```

**必须等到 `READY` 再触发审查**——`PENDING` 表示切块与向量化未完成，此时的 Review 检索不到它。

### D2 切块与向量落库

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select d.id, d.title, count(c.id) as chunks
from knowledge_document d left join knowledge_chunk c
  on c.project_id=d.project_id and c.document_id=d.id
group by d.id, d.title order by d.id;"
```

READY 的文档 `chunks` 应大于 0。

### D3 批量上传

**操作**：一次选择多个文件上传。

**预期**：逐个成功，**每个文件各自一个事务**——其中一个失败不影响其他。

**验证**：故意混入一个超大或格式不支持的文件，确认其余仍然成功、失败的那个状态为 `FAILED` 且 `failure_reason` 非空。

### D4 知识文档硬删除

**操作**：删除一个已 READY 的知识文档。

**预期**：文档与其全部 chunk **同事务一并删除**，不留孤儿。

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select count(*) as orphan_chunks from knowledge_chunk c
where not exists (select 1 from knowledge_document d
                  where d.project_id=c.project_id and d.id=c.document_id);"
```

必须为 **0**。

### D5 向量索引元数据可见

**操作**：查看知识页展示的向量索引信息。

**预期**：展示的是**真实**的索引元数据（维度、是否有索引等），不是写死的文案。

> 诚实说明：**语义检索目前没有向量索引**，走顺序扫描的精确余弦序。这是决策不是遗漏——冻结的 Embedding Profile 是 4096 维，超过 pgvector 0.8.6 全部精确索引形态的维度上限，可建的两种形态都是有损预筛。

---

## 6. E 组：需求与验收条件

### E1 创建需求（DRAFT）

**操作**：**研发需求**（`/requirements`）→ 选中项目 → 新建需求，填标题、背景、描述，并添加多条验收条件。

**预期**：需求状态 `DRAFT`，Revision 1 生成。

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select r.id, r.project_id, r.status, rv.seq, rv.title, r.current_revision_id
from requirement r join requirement_revision rv on rv.id=r.current_revision_id
order by r.id;"
```

**记下 `r.id`——它是全局递增的，不是项目内编号，F 组要用。**

### E2 DRAFT 阶段可原地编辑

**操作**：修改 DRAFT 需求的标题、描述与 AC。

**预期**：直接改在 Revision 1 上，**不产生新 Revision**。

**验证**：`select count(*) from requirement_revision where requirement_id=<id>;` 仍为 1。

### E3 稳定的 ac_key

**操作**：记录每条 AC 的 `ac_key`，然后调整 AC 顺序或修改其中一条的文字。

**预期**：**已有 AC 的 `ac_key` 不变**（它是 Finding 引用的锚点，变了会打断跨轮追溯）。

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select requirement_revision_id, ac_key, sort_order, left(text,40)
from acceptance_criterion order by requirement_revision_id, sort_order;"
```

### E4 需求质量检查

**操作**：对需求运行质量检查。

**预期**：给出**建议**。

**关键**：质量检查**不是工作流状态**，不阻塞任何操作，也**不会自动把需求置为 READY**。DRAFT 期间正文一改，质量检查结果即失效。

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id, requirement_id, seq, quality_version, quality_checked_at,
       (quality_json is not null) as has_result
from requirement_revision order by id;"
```

**对应端点**：`POST /api/projects/{projectId}/requirements/{requirementId}/quality`

### E5 DRAFT → READY 冻结

**操作**：LEADER 把需求置为 READY，然后尝试修改正文或 AC。

**预期**：READY 之后正文与 AC **锁定**，无法原地修改。

### E6 创建新 Revision

**操作**：对已 READY 的需求发起修改，填写**变更原因**。

**预期**：生成 Revision 2，`current_revision_id` 指向新版本，**旧 Revision 与旧 AC 永久保留**。

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id, requirement_id, seq, left(title,30) as title, change_reason, created_at
from requirement_revision where requirement_id=<id> order by seq;"
```

### E7 指派与状态推进

**操作**：把 READY 需求指派给某个 DEVELOPER。

**预期**：**首次指派与 `READY → IN_DEVELOPMENT` 同事务完成**。后续更换负责人**不再改变状态**。

**验证**：指派后 `status` 应为 `IN_DEVELOPMENT`；再换一个负责人，状态保持不变。

### E8 需求文档附件

**操作**：上传 `.txt` / `.md` 需求文档作为附件，然后**阅读**与**下载**。

**预期**：三个动作都成功。上传由 LEADER 执行；阅读与下载所有成员都可以。

**对应端点**：`POST .../attachments`、`GET .../attachments/{documentId}/content`、`GET .../attachments/{documentId}/download`

### E9 附件隔离与显式提升

**操作**：在需求 A 上传附件，然后在需求 B 的 AI 场景里检查该附件是否被检索到。

**预期**：**不会**。需求附件只在所属需求的 AI 场景可见。要跨需求共用，必须**显式提升为项目知识**。

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id, project_id, source_type, source_requirement_id, title, status
from knowledge_document order by id;"
```

附件类文档 `source_type = REQUIREMENT_ATTACHMENT` 且 `source_requirement_id` 非空；提升后应变为 `PROJECT_KNOWLEDGE` 且该列为空。

**对应端点**：`POST .../attachments/{documentId}/promote`

### E10 一次性 AI 实现建议

**操作**：在需求详情页生成实现建议。由 LEADER 或**被指派的** DEVELOPER 触发。

**预期**：生成一份实现建议。**每条需求只能生成一次**，再次尝试应被拒绝。

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id, use_case, model, status, latency_ms, total_token, created_at
from ai_call_log where use_case='IMPLEMENTATION_GUIDANCE' order by id desc limit 5;"
```

### E11 结构化 Markdown 导出

**操作**：导出需求。

**预期**：得到结构化 Markdown，包含标题、背景、描述与全部 AC。

### E12 作废需求（软删除）

**操作**：LEADER 取消/作废一条需求。

**预期**：需求进入 `CANCELED` 并**软删除**（`deleted_at` 与 `deleted_by` 同时写入），列表中不再出现，但**历史审计与既成事实保留**。

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id, status, deleted_at, deleted_by from requirement order by id;"
```

`deleted_at` 与 `deleted_by` 必须**同时**为空或**同时**非空（有 CHECK 约束保证）。

---

## 7. F 组：推送 → PR → Webhook → Review

这是核心链路，务必逐步验证。

### F1 建分支并改代码

```bash
cd ~/fp-test/<repo>
git checkout main && git pull
git checkout -b feature/req-<需求全局id>-<简短描述>
# ... 编辑代码 ...
git add -A
git commit -m "REQ-<需求全局id> <改动说明>"
git push -u origin HEAD
```

> **建议第一轮故意留一个漏项**（比如需求要求修三处，你只修两处）。第一轮全对反而看不到 Finding，演示不出效果。

### F2 开 PR，标题带上需求编号

```bash
gh pr create -R <owner>/<repo> \
  --base main --head feature/req-<id>-<描述> \
  --title "REQ-<需求全局id> <需求标题>" \
  --body "## 需求
REQ-<需求全局id> <需求标题>

## 改动
- ...

## 自测
- ..."
```

**编号规则（最容易出错的一处）**：

系统解析的是 **`REQ-<全局 requirement id>`**，正则为：

```
(?<![A-Za-z0-9])REQ-(\d{1,18})
```

- 是 E1 里记下的**全局 id**，**不是**「本项目第几条需求」。
- `REQ-` 前面不能紧挨字母或数字：`[Demo] REQ-7 ...` 可以，`XREQ-7` 不行。
- 标题和分支名都会被解析，任一命中即可。

### F3 确认 Webhook 投递成功

```bash
HOOK=$(gh api repos/<owner>/<repo>/hooks --jq '.[0].id')
gh api repos/<owner>/<repo>/hooks/$HOOK/deliveries \
  --jq '.[0:5][] | "\(.delivered_at) \(.event) -> \(.status_code)"'
```

**预期**：`pull_request -> 202`。

### F4 确认 PR 入库并自动关联需求

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id, project_id, repository_id, external_number, left(title,40) as title,
       author_username, requirement_id, left(head_sha,8) as head,
       left(review_input_fingerprint,8) as fp,
       jsonb_array_length(changed_files) as files
from pull_request order by id desc limit 5;"
```

**预期**：`requirement_id` 非空。

**证明是真投递而非手工塞数据**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id, pull_request_id, from_requirement_id, to_requirement_id,
       actor_type, actor_user_id, reason, created_at
from pull_request_requirement_event order by id desc limit 5;"
```

`actor_type = SYSTEM`、`reason` 形如 `Linked from REQ-7 in the pull request title` —— **只有真实 webhook 摄入路径会写出这一行**。

### F5 解析失败不阻断入库

**操作**：提一个**标题里没有** `REQ-<id>` 的 PR。

**预期**：PR **照常入库**，只是标记「未关联需求」，Review 仍然创建。**不得**因为解析失败就丢弃投递。

### F6 手工修改 PR ↔ 需求关联

**操作**：在代码审查页用需求下拉框为 F5 的 PR 选中需求；再试着清除关联。

**预期**：可改可清除。

**权限边界**：
- LEADER **始终**可改；
- DEVELOPER 只能改**本人的** PR，且**当前 head 尚无任何人工终局 Decision**；
- REVIEWER 不可改。

**验证**：改完后 `pull_request_requirement_event` 应新增一行，`actor_type` 为用户类型且 `actor_user_id` 非空——与 F4 的 `SYSTEM` 形成对照。

**对应端点**：`PUT /api/projects/{projectId}/pull-requests/{pullRequestId}/requirement`

### F7 Review 自动创建与执行

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id, project_id, pull_request_id, left(head_sha,8) as head,
       left(review_input_fingerprint,8) as fp,
       requirement_id, requirement_revision_id,
       status, decision, execution_attempt, engine, prompt_version, model, created_at
from review order by id desc limit 5;"
```

**预期**：Review 先以 `PENDING` 落库（与 PR 同步在同一事务内），提交后执行器启动，状态走向 `RUNNING → COMPLETED`。

**AI 调用留痕**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id, review_id, use_case, model, status, latency_ms,
       prompt_token, completion_token, total_token, left(coalesce(error,''),60) as err
from ai_call_log order by id desc limit 10;"
```

### F8 一个需求多个 PR

**操作**：再提一个 PR，标题引用**同一个** `REQ-<id>`。

**预期**：两个 PR 都关联到该需求。**一个需求可有多个 PR；一个 PR 至多关联一个需求。**

### F9 分批审查与未审查文件显式呈现

**操作**：提一个**改动文件很多**的 PR。

**预期**：分批审查但**只产出一份报告**；若有文件未被审查，必须**显式呈现**在报告里，**禁止静默截断**。

**边界**：changed-file 数量超限时整条投递按 **422** 拒绝。

> 已知限制：**超限投递不留痕**，运维看不到「有 PR 因过大被拒」。这是如实记录的缺口。

---

## 8. G 组：Finding 生命周期

### G1 查看 Finding 与证据

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id, project_id, review_id, requirement_revision_id, ac_id,
       status, continuity, confidence, left(title,50) as title
from finding order by id;"
```

**预期**：每条 Finding 带证据——命中的 AC、代码位置、引用的项目知识片段。

### G2 三个正交概念分开呈现

**检查界面上这三者是分开的标签，不得合并**：

| 概念 | 取值 | 含义 |
|---|---|---|
| **status** | OPEN / CONFIRMED / IN_PROGRESS / FIXED / VERIFIED / CLOSED / REJECTED | 人工处理到哪一步 |
| **continuity** | NEW / PERSISTING / SUPPRESSED | 跨 Review 的血缘 |
| **confidence** | HIGH / MEDIUM / LOW | 模型自报把握 |

**置信度只分三档、不给数值，且不参与任何自动门禁或状态流转。** 它是**未经校准**的模型自报把握，不是质量保证。

### G3 确认与驳回（LEADER / REVIEWER）

**操作**：用 REVIEWER 账号把一条 Finding 从 `OPEN` 置为 `CONFIRMED`；再把另一条置为 `REJECTED`。

**预期**：都成功。

**关键**：**普通驳回不可逆**（`REJECTED` 是终态）。

### G4 认领与标记已修复（DEVELOPER）

**操作**：用 DEVELOPER 账号 `CONFIRMED → IN_PROGRESS`，改完代码后 `IN_PROGRESS → FIXED`。

**预期**：都成功。

**关键**：这两步是 **DEVELOPER 专属**，LEADER 和 REVIEWER **做不了**。用 LEADER 账号尝试应返回 403。

### G5 复验通过与打回（LEADER / REVIEWER）

**操作**：`FIXED → VERIFIED`（通过）或 `FIXED → IN_PROGRESS`（打回）。

**预期**：两条路都能走通。

### G6 关闭

**操作**：`VERIFIED → CLOSED`。

**预期**：成功。`CLOSED` 是终态。

### G7 非法流转被拒

**操作**：尝试 `OPEN → FIXED`、`CLOSED → 任意`、`REJECTED → CONFIRMED`。

**预期**：全部被拒绝。合法流转表如下：

```
OPEN      → CONFIRMED, REJECTED
CONFIRMED → IN_PROGRESS, REJECTED
IN_PROGRESS → FIXED
FIXED     → VERIFIED, IN_PROGRESS
VERIFIED  → CLOSED
CLOSED    → （终态）
REJECTED  → OPEN（仅限 continuity=SUPPRESSED 的继承驳回项）
```

### G8 人工决策留痕

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id, finding_id, from_status, to_status, actor_user_id, note, created_at
from finding_event order by id;"
```

**预期**：每次人工流转都有 actor、时间、备注，可追溯。

---

## 9. H 组：复审、决策与需求收尾

### H1 推新 head 使前一次 Review 变历史

```bash
cd ~/fp-test/<repo>
# 修复 Finding 指出的问题
git add -A && git commit -m "fix: 按 Review 反馈修复"
git push
```

**预期**：新 head 一到，**上一次 Review 立即变成历史**，新的一轮开始。

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select rv.id, rv.pull_request_id, left(rv.head_sha,8) as review_head,
       left(pr.head_sha,8) as pr_head,
       (rv.head_sha = pr.head_sha) as is_current,
       rv.status, rv.decision
from review rv join pull_request pr on pr.id = rv.pull_request_id
order by rv.id;"
```

旧行 `is_current = false`。

**Review 的身份是 `(PR, head SHA, Diff fingerprint, 需求版本)` 四元组。**

### H2 跨轮抑制

**预期**：上一轮**已驳回**、且源码证据与判定依据都**未变**的 Finding，本轮**自动抑制**，不要求你重复驳回。

**验证**：新一轮里这条 Finding 的 `status=REJECTED`、`continuity=SUPPRESSED`。

**关键**：抑制**不跨 PR**，且**不得**把「本轮没有再报告」自动认定为「已修复」。

> 已知限制：`finding_key` 含 patch 新侧行号，无关插入造成行号整体移动时，同一条证据可能被判为 `NEW` 而继承不到上一轮的 `SUPPRESSED`。

### H3 重开被抑制项

**操作**：对一条 `status=REJECTED` 且 `continuity=SUPPRESSED` 的 Finding 执行重开。

**预期**：回到 `OPEN` 并正常显示，但 **`continuity` 仍保留 `SUPPRESSED`**（血缘事实不因当前状态改变而消失），且留审计。

### H4 需求版本变更不自动重审

**操作**：对已有关联 PR 的需求创建新 Revision。

**预期**：**不自动重审**，关联 PR 显示「审查已过期」，由人工按权限触发。

**对应端点**：`POST /api/projects/{projectId}/pull-requests/{pullRequestId}/reviews`

### H5 终局 Decision 与闸门

**操作**：REVIEWER 或 LEADER 对已 COMPLETED 的 Review 写入 `REQUEST_CHANGES`。

**预期**：写入成功。然后：

1. **再写一次**（任何值）→ 被拒绝。**Decision 只能从 PENDING 写入一次。**
2. **不推新 head 直接 APPROVE** → 被拒绝。同一 head 出现 `REQUEST_CHANGES` 后，**必须有新的 head SHA 才能再 APPROVE**。
3. 试图通过**改 Base、改需求关联、改需求版本、重新同步 Diff** 来解闸 → **都解不开**。
4. 推一个新 commit 后再 APPROVE → 成功。

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id, pull_request_id, left(head_sha,8) as head, status, decision,
       decision_by, decision_at, left(coalesce(decision_comment,''),40) as note
from review order by id;"
```

### H6 评审活动是只读派生量

**操作**：查看需求列表上的「评审活动」。

**预期**：它**不落表**，按当前 head + fingerprint + 需求版本实时计算：

| 情况 | Activity |
|---|---|
| 没有关联 PR | `NO_PR` |
| 没有匹配当前输入的 Review | `REVIEW_REQUIRED` |
| 执行失败 | `FAILED` |
| Decision 为 REQUEST_CHANGES | `CHANGES_REQUESTED` |
| RUNNING，或 COMPLETED 但等人工决策 | `REVIEWING` |
| PENDING | `PENDING` |
| Decision 为 APPROVE | `APPROVED` |

多 PR 聚合：`FAILED`、`CHANGES_REQUESTED` 依次占优；全部相同返回该状态；全 `APPROVED` 才 `APPROVED`；其余 `MIXED` 并展示各状态计数。

**需求状态与评审活动并列展示，不得合并。**

**对应端点**：`GET /api/projects/{projectId}/requirements/{requirementId}/review-activity`、`GET /api/projects/{projectId}/review-activity`

### H7 需求覆盖度与审查校准

**操作**：查看需求覆盖度与审查校准两个视图。

**对应端点**：`GET .../requirements/{requirementId}/coverage`、`GET .../review-calibration`

### H8 需求 DONE 由人确认

**操作**：LEADER 在确认全部关联工作完成后，把需求置为 `DONE`。

**预期**：成功。

**关键**：
- 单个 PR 的 APPROVE **只结束当前 Review**，不代表需求完成；
- **AI、Webhook、PR、Review 一律不得推进需求状态**。

> 已知限制：**需求状态转换不单独留痕**。`DRAFT→READY`、指派、`CANCELED`、`DONE` 的转换本身不写审计行，这是明确接受的取舍。

---

## 10. I 组：通知

### I1 配置钉钉通知

**操作**：LEADER 在项目里配置钉钉通知渠道（Webhook 地址、可选签名、关键词）。

**对应端点**：`PUT /api/projects/{projectId}/notifications/dingtalk`

**验证**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id, project_id, enabled, created_at, updated_at
from project_notification_channel order by id;"
```

密钥类字段应为密文。

### I2 发送测试消息

**操作**：点击发送测试消息。

**预期**：钉钉群收到消息。仅 LEADER 可发。

**对应端点**：`POST /api/projects/{projectId}/notifications/dingtalk/test`

### I3 审查完成 / 失败通知

**操作**：触发一次成功的 Review 与一次失败的 Review。

**预期**：两种都收到摘要通知，且**消息中不含敏感信息**（token、完整 diff）。

### I4 删除通知渠道

**对应端点**：`DELETE /api/projects/{projectId}/notifications/dingtalk`

---

## 11. J 组：归档、删除与隔离

### J1 项目归档

**操作**：LEADER 归档项目，需**重新输入项目名**确认。

**预期**：输错名字不允许提交；确认后项目进入归档态。

**对应端点**：`POST /api/projects/{projectId}/archive`

### J2 归档态的只读性

**操作**：对已归档项目尝试新建需求、上传知识、触发审查。

**预期**：被拒绝。

### J3 恢复项目

**对应端点**：`POST /api/projects/{projectId}/unarchive`

**预期**：恢复后全部功能可用，数据完好。

### J4 三类资源三种删除策略

这是**有意为之的不一致**，由三组外键语义决定，不是妥协：

| 资源 | 策略 | 理由 |
|---|---|---|
| 知识文档 | **硬删** | 派生数据，同事务显式删 chunk |
| 成员 | **硬删** | 活权限必须真正消失 |
| 作废需求 | **软删** | 审计与既成事实必须保留 |

逐项验证见 D4、B7、E12。

### J5 删除台账

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select * from project_deletion_record order by id;"
```

> `resource_id` **故意没有外键**——被引对象按定义已不存在，没有可加外键的目标。这是「不建通用 audit_event」原则的唯一记录在案的例外。

---

## 12. K 组：负向与边界测试

这一组最能体现系统的可靠性，**建议不要跳过**。

### K1 权限矩阵逐格验证

用不同账号逐格测试，**每一个 ❌ 都应返回 403**：

| 动作 | LEADER | DEVELOPER | REVIEWER |
|---|:--:|:--:|:--:|
| 创建项目、管理成员与角色 | ✅ | ❌ | ❌ |
| 配置 SCM 仓库、上传项目知识 | ✅ | ❌ | ❌ |
| 验证、标注、撤销自己的 SCM 身份 | ✅ | ✅ | ✅ |
| 为项目选择自己的兼容 SCM 身份 | ✅ | ✅ | ✅ |
| 严格项目批准/拒绝待审绑定 | ✅ | ❌ | ❌ |
| 配置钉钉通知、发送测试消息 | ✅ | ❌ | ❌ |
| 创建/编辑需求与 AC | ✅ | ❌ | ❌ |
| 上传 `.txt/.md` 需求文档 | ✅ | ❌ | ❌ |
| 阅读/下载需求文档、导出需求 | ✅ | ✅ | ✅ |
| 运行需求质量检查 | ✅ | ❌ | ❌ |
| 需求 DRAFT→READY、指派开发 | ✅ | ❌ | ❌ |
| 生成一次性 AI 实现建议 | ✅ | 仅被指派需求 | ❌ |
| 修改 PR↔需求关联 | ✅ | 仅本人 PR，且当前 head 无终局 Decision | ❌ |
| 触发/重试 Review | ✅ | 仅本人 PR | ✅ |
| Finding 确认 / 拒绝 | ✅ | ❌ | ✅ |
| **Finding 认领、标记已修复** | **❌** | **✅** | **❌** |
| Finding 验证通过 / 打回 | ✅ | ❌ | ✅ |
| Review 终局 APPROVE / REQUEST_CHANGES | ✅ | ❌ | ✅ |
| 取消需求 | ✅ | ❌ | ❌ |

> 注意 LEADER 在「Finding 认领、标记已修复」这一行是 **❌**。单角色 LEADER **走不完** Finding 主链，这是设计而非缺陷。

### K2 「仅本人 PR」按身份而非用户名判定

**操作**：让 dev01 提 PR，然后用 dev02 尝试修改该 PR 的需求关联或触发重审。

**预期**：403。判定按 **Provider + 实例 + 稳定外部用户 ID** 与成员当前活动绑定，**禁止按用户名授权**。

### K3 Webhook 签名伪造

**操作**：手工构造一个请求直接 POST 到 `/api/scm/github/webhook`，签名随便填。

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://<域名>/api/scm/github/webhook \
  -H 'Content-Type: application/json' \
  -H 'X-GitHub-Event: pull_request' \
  -H 'X-Hub-Signature-256: sha256=deadbeef' \
  -d '{"repository":{"id":1,"html_url":"https://github.com/x/y"},"number":1}'
```

**预期**：**401**，且**不写任何数据、不发起任何拉取**。

**再测**：畸形 JSON、超长 DNS label、把该出现字符串的地方换成对象——**全部返回与签名错误完全相同的 401 响应体**（不可区分性）。

### K4 AI 返回非法结构

**预期**：Review 判定**失败**，**绝不生成「成功的空报告」**。

**验证**：`review.status = FAILED`，且 `ai_call_log` 有对应的失败记录。

### K5 FAILED 重试复用同一行

**操作**：对一个 FAILED 的 Review 触发重试。

**预期**：**复用同一行**（`execution_attempt` 递增），而不是新建一行。**COMPLETED 的 Review 永不被覆盖。**

### K6 并发决策只有一个成功

**操作**：同时发起两个 APPROVE / REQUEST_CHANGES 请求。

**预期**：只有一个成功，另一个被拒。

### K7 停滞任务的对账恢复

**操作**：在 Review 执行中重启后端容器。

```bash
docker restart fp-demo-backend-1
```

**预期**：reconciliation 恢复已落库但未执行/停滞的任务。

**关键**：**reconciliation 不得补建缺失的 Review**——它只恢复已存在的行。

> 进程内 Review 执行**不提供**消息队列级持久性。每次执行使用 attempt/token fencing，**过期 Worker 不得覆盖新结果**。

### K8 项目隔离

**操作**：用项目 A 的 id 拼接项目 B 的资源 id 发起请求。

**预期**：全部拒绝。所有项目内引用与查询都必须保持 `project_id` 隔离。

### K9 Review 历史语义不被当前关联改写

**操作**：改掉 PR 当前关联的需求，然后查看**历史** Review。

**预期**：历史 Review 展示的仍是**它当时**保存的 `requirement_id`、`requirement_revision_id` 与不可变上下文快照，**不得**通过 PR 当前关联反查语义。

---

## 13. 排错手册

### Webhook 没反应

```bash
HOOK=$(gh api repos/<owner>/<repo>/hooks --jq '.[0].id')
gh api repos/<owner>/<repo>/hooks/$HOOK/deliveries \
  --jq '.[0:5][] | "\(.delivered_at) \(.event) -> \(.status_code) \(.status)"'
```

| 状态码 | 含义 | 处理 |
|---|---|---|
| **202** | 正常 | 往下查 `pull_request` 表 |
| **401** | 签名校验失败 | 两侧 secret 不一致。重设新值、两边同时写 |
| **422** | 被拒绝 | changed-file 超限。**这种情况不留痕** |
| **5xx** | 后端异常 | `docker logs fp-demo-backend-1 --tail 200` |
| 无投递记录 | hook 没建或没开 | 检查 `active` 与 `events` 是否含 `pull_request` |

### PR 进来了但没关联需求

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id, project_id, external_number, left(title,60) as title, requirement_id
from pull_request order by id desc limit 5;"
```

`requirement_id` 为空，绝大多数是**标题里的编号不是全局 id**。按 F6 手工关联，或改标题后推一个新 commit 重新触发。

### Review 一直 PENDING 或 FAILED

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id, status, decision, execution_attempt, lease_until, engine, model
from review order by id desc limit 5;"

docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select id, review_id, use_case, model, status, latency_ms, left(coalesce(error,''),80) as err
from ai_call_log order by id desc limit 10;"
```

- 长时间 `PENDING`：执行器没启动或已停滞，reconciliation 会兜底。
- `FAILED`：看 `ai_call_log.error`。

### 知识检索不到

确认文档 `status = READY` 且 `knowledge_chunk` 有行（见 D1、D2）。`PENDING` 状态下的文档不参与检索。

### 权限报 403 但你觉得应该有权

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select pm.project_id, u.username, string_agg(r.role,'+' order by r.role) as roles
from project_member pm join user_account u on u.id=pm.user_id
left join project_member_role r on r.project_id=pm.project_id and r.user_id=pm.user_id
group by pm.project_id, u.username order by pm.project_id;"
```

对照 K1 的权限矩阵。角色是**每次请求现查**的，改完立即生效，不需要重新登录。

### 日志

```bash
docker logs fp-demo-backend-1 --tail 200
docker logs fp-demo-frontend-1 --tail 100
```

---

## 14. 附：常用查库速查

**全局概览**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select 'user_account' t, count(*) from user_account
union all select 'project', count(*) from project
union all select 'project_member', count(*) from project_member
union all select 'scm_repository', count(*) from scm_repository
union all select 'scm_identity', count(*) from scm_identity
union all select 'knowledge_document', count(*) from knowledge_document
union all select 'knowledge_chunk', count(*) from knowledge_chunk
union all select 'requirement', count(*) from requirement
union all select 'acceptance_criterion', count(*) from acceptance_criterion
union all select 'pull_request', count(*) from pull_request
union all select 'review', count(*) from review
union all select 'finding', count(*) from finding
union all select 'ai_call_log', count(*) from ai_call_log
order by 1;"
```

**一条链路端到端**：

```bash
docker exec fp-demo-postgres-1 psql -U forgepilot -d forgepilot -c "
select p.name as project, rv.title as requirement, r.status as req_status,
       pr.external_number as pr, left(pr.head_sha,8) as head,
       rev.status as review_status, rev.decision,
       count(f.id) as findings
from project p
join requirement r on r.project_id = p.id
join requirement_revision rv on rv.id = r.current_revision_id
left join pull_request pr on pr.requirement_id = r.id
left join review rev on rev.pull_request_id = pr.id
left join finding f on f.review_id = rev.id
group by p.name, rv.title, r.status, pr.external_number, pr.head_sha,
         rev.status, rev.decision
order by p.name, rv.title;"
```

**重置为空库重来**（先备份）：

```bash
docker exec fp-demo-postgres-1 pg_dump -U forgepilot -d forgepilot \
  > ~/fp-backup-$(date -u +%Y%m%dT%H%M%SZ).sql

docker exec -i fp-demo-postgres-1 psql -U forgepilot -d forgepilot <<'SQL'
BEGIN;
TRUNCATE TABLE
  acceptance_criterion, ai_call_log, finding, finding_event,
  knowledge_chunk, knowledge_document,
  project, project_deletion_record, project_member, project_member_role,
  project_member_scm_binding, project_notification_channel,
  pull_request, pull_request_requirement_event,
  requirement, requirement_attachment, requirement_revision,
  review, scm_identity, scm_repository
RESTART IDENTITY CASCADE;
COMMIT;
SQL
```

**注意**：上面这条**不动 `user_account`**。要连账号一起清，另加 `user_account` 到列表里，但那样你自己的账号也会没有。

---

## 15. 测试中要如实记录的边界

以下是产品的真实限制，测试时遇到不要当成 bug，答辩被问到时主动说明：

1. **语义检索没有向量索引**，走顺序扫描的精确余弦序。冻结的 Embedding Profile 是 4096 维，超过 pgvector 0.8.6 全部精确索引形态的维度上限，可建的两种形态都是有损预筛。
2. **模型置信度未经校准**，只分三档，不参与任何门禁或状态流转。
3. **需求状态转换不单独留痕**。
4. **超限 changed-file 投递不留痕**。
5. **Finding 行号连续性**：`finding_key` 含 patch 新侧行号，无关插入造成行号移动时同一证据可能被判为 `NEW`。
6. **一个项目一个仓库**；仓库产生 PR 后身份冻结。
7. **进程内 Review 执行不提供消息队列级持久性**，靠 reconciliation 兜底，且 reconciliation 不补建缺失 Review。
8. **浏览器点击闭环、1440/768/390 三档宽度与 `prefers-reduced-motion` 两种模式为人工验收**，未自动化。
9. **PostgreSQL 最低版本 15**（复合外键列级 `ON DELETE SET NULL` 与 `UNIQUE NULLS NOT DISTINCT` 两处语法不可替代）。
