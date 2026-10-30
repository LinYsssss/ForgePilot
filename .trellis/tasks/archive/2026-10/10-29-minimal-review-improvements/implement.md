# 执行顺序

- [x] A：提示词/校验/枚举与标签，复用 Validator/Batcher/Pipeline 及读模型测试，定向验证。
- [x] B：原聚合 SQL/DTO/工作台表格，复用 Insight/工作台测试，定向验证。
- [x] C：原决定与 Provider 关闭/状态同步、一次追加迁移、前端危险操作与通知，集中测试版本、权限、确认/不明、补记和队列/迁移兼容。
- [x] 更新直接受影响的 PRD/ARCHITECTURE/API/规范，检查冻结资产无差异。
- [x] 运行 frontend lint/typecheck/test/build 与标准 Docker JDK21 backend verify 一次；失败修复后只重跑受影响检查。
- [x] 按授权独立主题提交、推送、部署并核验；更新审计阶段报告，恢复父任务继续剩余全量阅读（父审读尚未完成）。

不执行付费模型推理、真实 PR 合并/关闭或外发测试消息。若 C 需要新增恢复系统，保留独立未完成状态并说明原因，不扩张实现。

## 验证证据（2026-10-29）

- A 定向后端 60 个测试通过；B 后端 11、工作台 5 个测试通过；C 前端定向 21 个测试通过。
- 后端完整 verify 覆盖 443 个测试，首轮两个测试契约遗漏；修正假 PR 的开放状态及只读 SCM 交互断言后，复查 Pipeline/Notification/Activity 共 35 个测试零失败，verify 构建成功。最终 XML 汇总覆盖 443 个测试，保留完整初跑与定向复查日志，不声称完整初跑零失败。
- 前端完整回归覆盖 70 个测试，首轮三处旧文案/空台账 fixture 不符；修正后受影响三个文件共 12 个测试通过，最终全部测试已通过。lint/typecheck/build 均通过，类型复用收尾再次 typecheck 通过。
- 新承重边界：终态 PR 不领取 PENDING、RUNNING 租约仍恢复；在途 OPEN 不能覆盖关闭、真实外部重开可同步；已拒绝同版本重开退出活动聚合，新 head 仍待审；重复决定不重复关闭或通知。
- 9 个顶层包、21 张业务表、15 份迁移；冻结 evaluation/holdout 资产无差异。`git diff --check` 通过。
- 原始日志位于服务器 `/tmp/forgepilot-minimal-*.log`，发布证据目录 `/root/forgepilot-reports/minimal-review-20261029/`。部署前新备份已核验目录，未执行恢复演练。

## 交付核验

- 三个独立提交已推送：A `e14b663`、B `4bf649c`、C `5021013e455504e65b5ee4f221e42488be3c3921`。
- 此 HEAD 的干净远程 CI [40912586600](https://github.com/LinYsssss/ForgePilot/actions/runs/40912586600) 全部通过：backend verify 443 个测试零失败/错误/跳过；前端门禁、评测契约确定性复算及两次 fresh-volume compose smoke 通过。远程结果独立于本地初跑/复查，不改写本地失败记录。
- `docker compose -p fp-demo up --build --detach --wait` 成功。2026-10-30 01:21 UTC 重新核验：远程 main、业务代码及部署相同；三服务 healthy，本地/公网 UP；JS/CSS 字节一致，后端 511 个非目录 payload 条目一致；15 条迁移成功，数据库原容器/卷保留，发布前后业务聚合相同。
- 新备份目录核验成功，SHA-256 `e1f609ecd44ccf1903fcd692e1925b07d33c750fbf92c959f8172c226c935410`；未执行恢复演练。
- 浏览器只读走查：工作台、PR 历史、审查详情 × 1440/768/390 × 两种动效，共 18 组，无整页横向溢出、alert 或页面脚本错误；核对请求模型列、远端状态，指定审查者打开拒绝确认框并取消。登录后拦截非 GET/HEAD，业务写请求为 0。未执行真实 PR 关闭/合并、外发通知或模型推理。
- `review-6` 没有真实推理样本，本轮证明行为契约与部署一致，不证明模型准确率改善。完整逐文件审读仍由 `10-29-sync-deploy-audit` 跟踪。
