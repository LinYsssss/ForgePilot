# 实施步骤

用户 2026-10-01 已批准复核后实施，依据 `/root/.claude/plans/zany-twirling-fern.md`。复核结论：无阻碍；补充空字符串/null 归一化细节，避免脏状态误判。无范围扩张。

- [x] 完成三份任务文件及规范清单，切换当前任务，保留 review5-fixes 未提交内容。
- [x] 修改 ImplementationGuidanceService / ImplementationGuidance：新字段、Prompt、schema 与受控解析错误。
- [x] 修改 frontend requirement/api.ts / RequirementDetailPage.vue：脏状态、请求失效、清楚的呈现、复制/导出。
- [x] 扩展 ImplementationGuidanceTest、requirement.spec.ts；journey.spec.ts 仅更新必要 fixture。
- [x] 只同步 API/PRD/ARCHITECTURE/MANUAL 相关段落及必要规范。
- [x] 后端完整补验、前端四道门、冻结/订正与 diff 检查，记录真实结果和未验收项。首次工具超时及后续独立容器补验见 validation.md。

## 必要回归

- 后端：新增字段/版本；缺失字段、错误数组、空白 summary 不得返回成功空结果。保留角色/项目隔离/调用次数/无新增存储测试。
- 前端：新增内容及默认折叠；未保存不得请求；DRAFT 同 revisionId 保存后的旧回答不得回流；重新生成失败保留有效旧结果；复制和导出相同文本、失败提示。
- 不新建测试类/工程，不改历史测试文件名。只做有明确不变量的断言。

## 命令

```bash
docker run --rm --network host -v /root/ForgePilot/backend:/workspace \
  -v /root/.m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock \
  -w /workspace eclipse-temurin:21-jdk ./mvnw -B -ntp verify
```

在 frontend/ 依次执行 lint、typecheck、`npm test -- --run`、build；依赖未变不重复安装。后端先跑全量一次，失败才定向排查，不把定向通过当全量通过。

仓库根运行 `formal_evaluation.py verify-freeze`、`postfreeze_provider_correction.py verify` 和 `git diff --check`。

浏览器可用时验收等待、脏状态、失效、失败保留、折叠和下载；没有条件则明确标记，不安装新浏览器测试工程。3–5 条真实模型案例属于后续单独批准事项，本轮不调用。

## 交付

保存 validation.md 并提供本任务独立改动与拟提交分组，不擅自提交、推送或部署，不归档先前任务。
