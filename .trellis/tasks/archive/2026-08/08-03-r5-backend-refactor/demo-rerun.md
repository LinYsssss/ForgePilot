# r5 终验:部署服务器演示动线复跑(重构后后端)

日期:2026-08-10 · 后端镜像:重构完成后 `docker compose build backend && up -d backend` 重建(代码 = 251b8c5)· 执行者:主会话(容器内 curl)

## 动线:登录 → 审查 → 报告 → Agent 工作台

| 步骤 | 端点 | 结果 |
| --- | --- | --- |
| CSRF 引导 | `GET /api/auth/csrf` | 200,XSRF cookie 落地 |
| 登录(种子管理员) | `POST /api/auth/login` | 200,身份 `reposage-e2e-admin` |
| 项目列表 | `GET /api/projects` | 200,1 项(展示项目 23) |
| 仓库详情 | `GET /api/projects/23/repository` | 200(repositoryId/repoUrl/provider/defaultBranch) |
| 提交列表 | `GET /api/projects/23/repository/commits` | 200,2 条 |
| 审查任务列表 | `GET /api/projects/23/reviews/tasks` | 200,分页信封 items/page/size/totalElements(冻结形状) |
| 审查报告列表 | `GET /api/projects/23/reviews/reports` | 200(该项目走的是 Agent 链,审查侧为空属实) |
| Agent 运行列表 | `GET /api/agent-runs/project/23` | 200,1 条(run18) |
| Run 详情 | `GET /api/agent-runs/18` | 200 |
| 时间线 | `GET /api/agent-runs/18/timeline` | 200,{run, steps} |
| Findings | `GET /api/projects/23/agent-runs/18/findings` | 200,**6 条**(展示件完好) |
| AI 调用日志 | `GET /api/ai/logs?projectId=23` | 200,5 条;缺参时 400 + 「projectId 或 taskId 至少传一个」+ traceId(校验行为正确) |
| 前端(nginx) | `GET http://localhost/` | 200 |

结论:重构后(批A/B/C 全量)部署服务器演示动线全绿,PRD 最后一条 AC 闭环。

## 过程中发现与处置

1. **展示项目 owner 悬挂**:r2 收尾清理删除了旧种子管理员(user 2),后端重启后按 `deploy/.env` 重新播种为 user 3,而项目 23 的 owner_id 仍指向已删除的 user 2 → 对全员不可见(ProjectAuthorization 冻结契约 no-admin-bypass,属清理遗留的数据悬挂,非重构问题)。处置:按「动库先备份」规则先 `backup.sh`(`code_review-20260810-063001.dump`),后 `UPDATE project SET owner_id=3 WHERE id=23`(1 行)。
2. **观察项(不在 r5 行为不变约束内动)**:不存在的 API 路径落到 `NoResourceFoundException`,被 GlobalExceptionHandler 兜成 500「未捕获的异常」;更合理的映射是 404。记入待办,留给后续任务定夺。
