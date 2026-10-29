# 执行顺序

- [x] A：提示词/校验/枚举与标签，复用 Validator/Batcher/Pipeline 及读模型测试，定向验证。
- [x] B：原聚合 SQL/DTO/工作台表格，复用 Insight/工作台测试，定向验证。
- [x] C：原决定与 Provider 关闭/状态同步、一次追加迁移、前端危险操作与通知，集中测试版本、权限、确认/不明、补记和队列/迁移兼容。
- [x] 更新直接受影响的 PRD/ARCHITECTURE/API/规范，检查冻结资产无差异。
- [x] 运行 frontend lint/typecheck/test/build 与标准 Docker JDK21 backend verify 一次；失败修复后只重跑受影响检查。
- [ ] 按授权独立主题提交、推送、部署并核验；更新审计阶段报告，继续剩余全量阅读。

不执行付费模型推理、真实 PR 合并/关闭或外发测试消息。若 C 需要新增恢复系统，保留独立未完成状态并说明原因，不扩张实现。

## 验证证据（2026-10-29）

- A 定向后端 60 个测试通过；B 后端 11、工作台 5 个测试通过；C 前端定向 21 个测试通过。
- 后端完整 verify 覆盖 443 个测试，首轮两个测试契约遗漏；修正假 PR 的开放状态及只读 SCM 交互断言后，复查 Pipeline/Notification/Activity 共 35 个测试零失败，verify 构建成功。最终 XML 汇总覆盖 443 个测试，保留完整初跑与定向复查日志，不声称完整初跑零失败。
- 前端完整回归覆盖 70 个测试，首轮三处旧文案/空台账 fixture 不符；修正后受影响三个文件共 12 个测试通过，最终全部测试已通过。lint/typecheck/build 均通过，类型复用收尾再次 typecheck 通过。
- 新承重边界：终态 PR 不领取 PENDING、RUNNING 租约仍恢复；在途 OPEN 不能覆盖关闭、真实外部重开可同步；已拒绝同版本重开退出活动聚合，新 head 仍待审；重复决定不重复关闭或通知。
- 9 个顶层包、21 张业务表、15 份迁移；冻结 evaluation/holdout 资产无差异。`git diff --check` 通过。
- 原始日志位于服务器 `/tmp/forgepilot-minimal-*.log`，发布证据目录 `/root/forgepilot-reports/minimal-review-20261029/`。部署前新备份已核验目录，未执行恢复演练。
