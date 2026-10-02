# review-5 实施步骤

审批来源：`/root/.claude/plans/zany-twirling-fern.md`，2026-09-30 已批准本地实施与验证；提交/推送/部署/真实模型复测不在本次授权内。

## 执行顺序

- [x] 同步 prd/design/implement，补真实 spec 引用；绑定本会话再 task.py start。加载 trellis-before-dev，不派生代理。
- [x] R1：ReviewPrompts 两个 schema 和指令恢复 type、版本 review-5；ReviewOutputValidator 合法引用优先校验，矛盾降级，缺失类型兼容。注释解释为何保留证据、不代表问题真实。
- [x] R2/R4：ReviewDecisionService 在现有 notReported/acKeysOf 上加私有同问题判定；correctsLine 只计 Finding；ReviewViews/api.ts/页面同步语义及局限。
- [x] R3：FindingLifecycleService 放开无人认领的开发者例外并明确拒绝消息；FindingCard 用既有 session 与 availableMoves 过滤按钮；状态/审计保持不变。
- [x] 同步架构、PRD/API/测试手册相关段落；论文补类型比例局限；交付说明只追加本次事实，不更新未部署的线上版本。
- [x] 完成必要回归断言与一次完整验证；如实记录失败或未做的手工验收。

## 必要测试

- ReviewOutputValidatorTest：分别调用 validator 验证两种矛盾，避免降级后的相同 key 正常去重混淆断言；覆盖缺失/未知类型兼容，沿用外来引用与证据拒绝测试。
- ReviewPipelineIntegrationTest：扩展现有 schema 同步测试，明确 required type 与两值枚举。
- ReviewDecisionTest：一个集中详情场景覆盖宽松重报、真正未报、跨修订同 AC、跨需求同名 AC 与空定位；混合 warning 的详情计数只含 Finding。
- FindingLifecycleTest：原非认领人拒绝断言后，调用现有 clearAssignee 路径，再由另一开发者标记并验证审计；原角色矩阵保留。
- frontend/tests/journey.spec.ts：在已有流程中补他人认领隐藏/无人认领显示，不另造测试工程。

不新建测试类、不改历史测试文件名，不增加无对应不变量的用例。

## 验证命令

后端只跑一次全量，失败才定向定位，不把单跑通过冒充全量通过：

```bash
docker run --rm --network host \
  -v /root/ForgePilot/backend:/workspace -v /root/.m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -w /workspace eclipse-temurin:21-jdk ./mvnw -B -ntp verify
```

frontend/：`npm run lint`、`npm run typecheck`、`npm test -- --run`、`npm run build`；依赖齐全且 lockfile 未变不重复 npm ci。

仓库根：

```bash
python3 evaluation/tools/formal_evaluation.py verify-freeze
python3 evaluation/tools/postfreeze_provider_correction.py verify
git diff --check
```

仅扫描本次改动文本的 U+FFFD 和过时契约。若没有真实浏览器条件，明确记录未完成手工按钮/提示验收，不能以 mock 测试替代该声明。

## R6 全仓审计追加步骤（已获批准）

- [x] 分类跟踪文件、扫描本地链接/锚点/语法及符号引用，核对配置和接口入口。
- [x] 更新当前 frontend/backend 规范、README、路由清单和确定的失真注释；保护历史证据，不为清理而删有效类型和框架入口。
- [x] 复核保护区与行为 token，记录 audit.md 中的修正/保留/未验证范围，更新提交清单；不自动提交部署。

## 提交与后续确认门

完成后呈现分组：① 类型修正及测试；② 详情/权限/文案及测试；③ 文档/任务。不为分组重复拆同一文件。经确认才在普通工作分支提交；不自动推送、不建 worktree。

另获部署批准后：保留旧镜像 → pg_dump → 串行构建 backend/frontend → up --detach --wait → 健康检查与页面抽查；无删除卷参数。回退旧应用镜像，不默认恢复库。

C 轮另行确认；不能直接跑现有 fp-stability-round.sh，它动态遍历开放 PR、checkout -B 且日期硬编码。先检查本地仓库、固定目标、使用实际日期与新标识，少量代表 PR 后再决定是否全量。记录失败、消耗和真实结果，不反复跑到比例好看，不覆盖 A/B 或正式评测。
