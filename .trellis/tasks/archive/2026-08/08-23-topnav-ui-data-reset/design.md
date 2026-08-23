# Design — 顶部导航、界面统一与演示数据重置

## 1. Shell

`AppShell.vue` 使用一个 sticky 顶部栏，不再渲染 `aside`。桌面网格采用左右等宽弹性列包围中间导航，使导航以视口内容区为基准真正居中：

```text
[横版 Logo / 产品描述] [六入口居中导航] [账号 / 退出]
```

在 `64rem` 以下变为两行：品牌和账户位于第一行，导航跨满第二行并可水平滚动；`42rem` 以下进一步收紧间距，但不隐藏入口。`main` 恢复全宽容器，不再为侧栏预留列。

## 2. Brand placement and login

- Signed-in Shell：只使用 `logo-lockup.png`。
- Login：只使用 `logo-app.png`，置于叙事区开头并与产品标题形成一个品牌锚点。
- Favicon：继续使用 `logo-app.png`。

登录页保留两栏因果链叙事，但减少右侧标题区重复品牌元素。表单卡成为右栏主视觉，登录/注册切换仍是同一表单，不改变 API 或 session 行为。移动端按既有断点变为单列。

## 3. Shared visual refinement

在现有 design token 上调整基础 CSS，不创建组件库：

- `page-head` 更紧凑，标题与说明形成稳定阅读宽度，并用轻量渐变/边线强调当前业务面。
- `project-selector`、`index-head`、`panel`、`record` 使用一致的间距、边框与 hover 层次。
- 主要内容顶部与 sticky header 保持合适距离。
- 长标题、模型名、仓库地址继续使用 `min-width:0`、换行和局部滚动，不开放页面级横向滚动。

`WorkspacePage.vue`、`KnowledgePage.vue`、`RepositoryPage.vue` 将过度压缩的单行模板拆开；现有真实数据和操作不变。Projects/Requirements/Reviews 主要通过共享基础样式和少量页面级调整获得一致布局。

## 4. Data cleanup

数据操作只针对当前 `fp-demo` Compose 项目。完成代码验证并部署后，在 `postgres` 容器中执行一个事务，显式 `TRUNCATE ... RESTART IDENTITY` 以下 15 张表。因为所有引用关系内的业务表都在同一条语句中，不需要扩大到 `CASCADE`：

```text
project, project_member, requirement, requirement_revision,
acceptance_criterion, knowledge_document, requirement_attachment,
knowledge_chunk, scm_repository, pull_request,
pull_request_requirement_event, review, finding, finding_event,
ai_call_log
```

`user_account` 不在目标清单。操作前后二次查询账号和各表计数；任何验证不符即停止，不删除卷，也不通过应用接口逐条删除。

## 5. Documentation and compatibility

D018 记录 D017 的侧边导航被用户明确改为顶部居中导航；六入口、Knowledge/SCM 正式页面、AI/向量展示边界均不改变。同步更新 Architecture 与 frontend design contract。

## 6. Validation

- Frontend：lint、typecheck、现有 route/session/journey focused tests、完整测试、build。
- Static：六入口不变、仅一个可见 Logo/页面、无依赖变更、无页面级横向溢出规则回退。
- Runtime：Compose 原地重建 frontend（必要时全栈等待健康），HTTP 路由/资源/health 检查。
- Database：唯一账号断言 + 15 张表零行断言。

## Rollback

前端回滚为代码/镜像回滚。数据库测试数据清理不可恢复，除非另有备份；用户已明确授权删除，因此执行前最后一次只读打印精确账号与行数，执行后保留计数证据。
