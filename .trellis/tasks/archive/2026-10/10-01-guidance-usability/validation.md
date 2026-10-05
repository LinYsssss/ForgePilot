# 验证记录：AI 实现建议（2026-10-01）

## 当前状态

功能实现、必要回归及相关文档已完成，业务代码已本地提交（review-5：`3248ffd`；guidance-2：`b56f670`），文档随当前批次记录；尚未推送、部署或调用真实模型。**后端完整门禁已在用户要求逐项继续后补齐：423 项通过，0 失败、0 错误、0 跳过。** 首次工具超时与定向验证记录保留如下；真实浏览器及模型输出质量仍未验收。

## 实现范围

- 业务文件 4 个：ImplementationGuidanceService、ImplementationGuidance、前端 requirement/api.ts、RequirementDetailPage.vue。
- 保留 checklist/rules/risks，新增 summary/questions/guidanceVersion=guidance-2；原字段增量兼容。
- 未保存内容禁止生成；DRAFT 同 revisionId 保存以及已知内容变化撤销旧建议/请求；旧 success/catch/finally 不改新状态。
- 来源默认折叠、生成状态明确、失败保留仍适用的上一份；复制与下载复用同一 Markdown 文本。
- 没有新增表、接口、依赖、历史存储、聊天或自动编码路径。

## 后端

### 首次全量尝试：因工具时限中止（历史记录）

命令使用仓库固定 JDK 21 容器入口：`./mvnw -B -ntp verify`。

工具后台任务 `bgmt4j806` 达到 **600 秒运行时限后被终止**。日志里已完成的测试类没有报告断言失败，但没有最终汇总/BUILD SUCCESS，不能算通过。确认该 Java 容器已结束，未再次启动同一全量任务。

原始日志：`/tmp/claude-0/-root/af1b8a97-60d1-4e7c-95e0-b746a61f86d9/tasks/bgmt4j806.output`。

### 本次变更定向回归：通过

同一容器入口运行：

```text
./mvnw -B -ntp -Dtest=ImplementationGuidanceTest test
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 07:00 min
```

覆盖新增字段与版本、非法结构拒绝、项目/角色隔离、调用次数、原有无建议存储边界。新增一条畸形回答回归方法，其余扩展既有测试；未新增/改名测试类。

日志：`/tmp/claude-0/-root/af1b8a97-60d1-4e7c-95e0-b746a61f86d9/tasks/b1xjapbkx.output`。

**定向通过不是全量通过。** 当时完整门禁仍待补齐，后续用户明确要求逐项继续后完成了以下长时补验。

### 逐项继续后的完整补验：通过

独立测试容器 `fp-guidance-verify-20261001t151842z` 使用原 JDK 21 镜像、网络和挂载执行 `./mvnw -B -ntp verify`；只将运行交给 Docker 持续承载，不再把整套验证绑定在 600 秒工具调用上。未同时重跑前端、未跳过测试、未改测试配置。

```text
Tests run: 423, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 18:26 min
```

- 完成时间：2026-10-01 15:51:28 UTC；容器退出码 0，OOMKilled=false。
- 后端 src、pom.xml、mvnw、wrapper 配置共 278 个输入文件，运行前后的路径/内容摘要均为 `30090af735e8032b42f969118abc720ad0cbc3eccde41ca8c25ab536057d1563`，测试期间源码未变。
- 完整日志：`/tmp/fp-guidance-verify-20261001t151842z.complete.log`，SHA-256 `d43258fa45ed3079c5e4e9d2585e41224d7306a4f22beeaa4beeddd907f08aeb`。
- 这次补验覆盖当前建议改动和保留的 review-5 改动；不是用前一次定向结果拼成全量结论。
- 日志保存、退出状态核实后已移除本次停止的临时测试容器；未删除卷、未重启生产服务。前端与建议源码快照仍与前次 66 项通过时相同。

## 前端

### 首次四道门

`npm run lint`、`npm run typecheck` 通过。`npm test -- --run` 失败：16 个文件的 43 项测试通过，但 reviewNavigation.spec.ts 和 requirement.spec.ts 的工作进程未成功启动，错误为：

```text
[vitest-pool]: Failed to start forks worker
[vitest-pool-runner]: Timeout waiting for worker to respond
Errors 2 errors
```

这不是相关测试通过，也不是已确认的断言缺陷；该命令的 build 因前一步失败未执行。

日志：`/tmp/claude-0/-root/af1b8a97-60d1-4e7c-95e0-b746a61f86d9/tasks/bjxc0qqnz.output`。

### 减少并行度后复验：通过

后端定向任务结束后运行：

```text
npm test -- --run --maxWorkers=1
Test Files 18 passed (18)
Tests 66 passed (66)
Duration 347.14s

npm run build
90 modules transformed
built in 12.96s
```

未修改项目测试配置、超时阈值或跳过文件；只在此机用单工作进程降低并行开销。新回归验证未保存不发请求、同 id 保存后的旧回答不能解除新请求的 pending、重新生成失败保留原结果、复制/下载文本相同、旧版缺字段不被解释成空结论。journey fixture 同步新增字段。

日志：`/tmp/claude-0/-root/af1b8a97-60d1-4e7c-95e0-b746a61f86d9/tasks/b3ljeukv9.output`。

仍有模拟 DOM 不实现 scrollTo/Canvas 的提示和构建插件耗时提示；不以此宣称真实浏览器已验收。

## 其他检查

本任务 7 个源码/测试文件按路径排序，将 `path + NUL + bytes + NUL` 连接后的 SHA-256 为 `ac0bfb4bcf60cc59e1243099dad1a45fa633cfec617d9f8c204c5611b91928eb`；上述定向与前端验证对应此快照，后续只更新文档。

- `formal_evaluation.py verify-freeze`：通过，仅校验，没有重跑实验。
- `postfreeze_provider_correction.py verify`：通过。
- evaluation、Flyway、旧论文 CSV/复算脚本与 HEAD 无差异。
- `git diff --check` 和 Trellis 上下文清单验证通过。
- API/PRD/ARCHITECTURE/MANUAL 相关说明已同步；前端状态规范补上同 id 草稿失效与旧响应保护契约。

## 尚未完成

- 真实浏览器验收：未执行，当前没有可用浏览器命令或 Python Playwright，未安装额外浏览器依赖。
- 真实模型 3–5 条建议样例：未执行，需单独确认调用和保存样本；mock 通过不证明生成内容质量。
- 业务代码已本地提交，推送、部署及任务归档未执行；后续动作另行确认。
