# Implement：后端分批重构

1. [ ] `trellis-before-dev` 载入 r4 规范。
2. [ ] 批A：`mvn dependency:analyze` + IDE 未引用分析 → 死代码清单落档 → 人工确认 → 删除；清理 `.worktrees/pr-gatekeeper-agent`；提交。
3. [ ] 批B：筛查 >500 行类 / >60 行方法清单落档 → 逐个拆分（公共签名不变，事务方法查自调用）→ 每拆完一组跑 `mvn verify` → 提交。
4. [ ] 批C：依赖图 → 反向依赖处置表（移动/反转/保留+理由）→ 纯移动提交与修改提交分离 → 每次移动后启动冒烟 → 提交。
5. [ ] 覆盖不足处先补特征测试（独立目录标注）。
6. [ ] 终验：`mvn -s .mvn/settings.xml verify` 绿、CI 绿、服务器演示动线复跑、契约零改动复核（REST 路径/DTO/迁移 diff 为空）。
7. [ ] `trellis-check`（Agent）→ 有价值教训走 `trellis-update-spec` → 提交推送 → `/trellis:finish-work`。

风险文件：全后端。回滚点：每批独立提交序列。
