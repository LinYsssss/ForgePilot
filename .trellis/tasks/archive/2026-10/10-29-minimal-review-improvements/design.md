# 最小实现设计

A 直接扩展 ReviewPrompts.appendCandidates 和生产 schema，版本改 review-6；最终 validate 接收 BatchPhase.evidence。结构解析成功后填补/降级 AC，保留 warnings；Finding 身份规则不变。直接扩展 AcVerdict 与现有标签，覆盖度读模型沿用枚举解析，无迁移。

B 改原 SQL group by model/use_case 和响应行，工作台原表增加模型列及局部加载失败/空记录状态；累计总数仍是 sum(calls)。不增加端点或费用估算。

C 复用原决定/SCM/通知入口，追加 REJECT 与 nullable PR 远端状态。状态不进指纹，Webhook 更新状态而不凭状态制造新审查。操作前权威核验，远端关闭确认后落本地拒绝；已关闭 PENDING 可核验补记，已合并/旧版本不能补记。通过闸门扩展同 head 的拒绝，保持一次性审计。完整边界按主计划，不做后台补偿。

终态过滤只阻止新审查与 PENDING 恢复，RUNNING 沿原围栏恢复。MERGED 不回退，CLOSED -> OPEN 于原行锁下再次只读核验，防止关闭期间已在途快照覆盖终态，允许实际重开。

兼容：旧 summary_json 与 NULL 远端状态保留；追加迁移不修改 V1–V14。回滚 A/B 可退代码；C 部署前备份，schema 追加保留，不能让旧代码误读新决定。各步先定向验证，最终既有完整门禁一次。
