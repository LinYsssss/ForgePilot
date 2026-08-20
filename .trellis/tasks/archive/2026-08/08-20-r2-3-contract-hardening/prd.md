# R2.3 契约加固与文档一致性修订

## Goal

在 Phase 1 底座开工前，修复 R2.2 方案中会导致实现返工或数据不一致的契约缺口，并把开发依据收敛为少量权威文档。此次只更新方案与治理状态，不写业务代码、不改变产品定位、不新增顶层模块或业务表。

## Requirements

- 保持 8 个顶层包、16 张表、单 Review Engine、无 MQ/Agent/Patch/第二 AI runtime 的既有边界。
- 为 Finding 增加永久父 Review 的 `(project_id, review_id)` 复合外键，并补充父 Review 与 Requirement/Revision 上下文的 NULL-safe 一致性规则。
- 明确 `PullRequestChanged` 为事务内同步事件：SCM 同步、PENDING Review 落库和事务提交后的执行器提交具有明确顺序；提交后调度失败由 reconciliation 恢复。
- 将 reconciliation 限定为恢复已落库但未执行或停滞的 PENDING/RUNNING，不得补建缺失 Review；任务领取和完成必须具备 attempt/token fencing 语义。
- 将 Review Decision 明确定义为一次性终局写入，补充 `decision=PENDING` 前置条件、并发条件更新和字段一致性约束。
- 将实际 Diff/Input fingerprint 纳入 Review 身份/有效性契约，区分 Review 身份、当前输入有效性和“REQUEST_CHANGES 必须新 head”闸门。
- 修正 PR 关联修改权限，使作者在自动创建 PENDING 后仍有可达的人工纠正路径；旧 Review 不覆盖，新上下文须人工重审。
- 增加 `REVIEW_REQUIRED`（或等价 `STALE`）派生活动，明确单 PR 映射、多 PR 聚合及 MIXED 测试矩阵。
- 消除需求附件的双事实源，补充 source type、source requirement、attachment relationship 的数据库/事务约束。
- 冻结 SCM 稳定实例身份并补充 webhook 权威快照、重复、乱序、并发和旧 head 回退防护契约。
- 让 Finding 抑制同时绑定源码证据和权威判定依据，避免需求/知识变更后错误继承误报。
- 清理 Phase 0/Phase 1 授权漂移、旧 R2 任务状态和文档中的过期表述；Phase 1 已授权进入任务级规划，但具体计划确认和 `task.py start` 前不得实施。
- 将 `FINAL-EXECUTION-PLAN.md`、`AI-HANDOFF.md`、`CLEANUP-AND-LEGACY.md` 与分散 ADR 的唯一有效内容分别并入实施计划、入口、迁移矩阵和统一决策记录；删除重复文件。
- `docs/v2/` 最终只保留 README、PRD、ARCHITECTURE、IMPLEMENTATION-PLAN、DECISIONS、LEGACY-MIGRATION-MATRIX 六份权威文档。

## Acceptance Criteria

- [ ] 六份权威文档与根入口文件对上述规则一致，且无相互矛盾的旧表述或死链接。
- [ ] 数据模型明确父 Review 外键、Review/ Finding 上下文约束、附件归属约束及所需唯一键；不增加业务表。
- [ ] Review 事务顺序、after-commit 调度、reconciliation 边界和 fencing 规则可直接转化为 Phase 5/6 测试。
- [ ] Decision 只能从 PENDING 成功写入一次，且并发 APPROVE/REQUEST_CHANGES 不会覆盖或反转结果。
- [ ] Review 输入指纹变化、需求版本过期、关联纠正和多 PR activity 聚合均有可观察定义。
- [ ] SCM 乱序/重放不会回退当前 PR 快照或重复创建 Review。
- [ ] Finding 抑制不会跨 PR、跨上下文依据错误继承；未报告不被自动当作已修复。
- [ ] 阶段闸门、每阶段 result 模板、测试/评测纪律和 Phase 1 任务级规划步骤均已进入 IMPLEMENTATION-PLAN。
- [ ] 旧 R2 任务不再阻塞 Phase 1；R2.3 任务本身有完整规划与验证记录并归档。
- [ ] 本次变更仅涉及文档/治理文件，业务源码目录保持不存在或未修改。

## Notes

- R2.3 是文档级契约加固；它不启动 Phase 1 实现，也不授权 Phase 2+。
- 尚未决定具体 Provider 字段名、数据库触发器实现或执行器类名时，只冻结可观察不变式；具体实现留给对应 Phase 的设计与必要的新决策记录。
