# Contract Testing Discipline(跨进程/跨模块数据格式的契约测试纪律)

> **规则**:任何跨进程或跨模块传递的数据格式——MQ 载荷、共享字符串引用、REST 契约、HMAC 规范化序列——必须有**"生产方真实产出驱动消费方"的双向契约测试**。**禁止两侧各自用手造数据测自己。**

---

## 为什么这是铁律(F-03 事故记录)

backend 产出 `workspace://agent-run-{id}-{sha}.tar`,sandbox-runner 的校验无条件拒绝含 `:` 的引用——**生产方的真实产出,消费方必拒**,链路 100% 断。而两侧测试全绿:backend 测试断言自己 `startsWith("workspace://")`,runner 测试只喂 `repo.zip` 这类自造夹具。**各测各的假数据,漂移在测试里完全不可见。** 完整复盘:`docs/adr/0001-工作区归档引用契约的单一事实源.md`。

同一缺陷类的进程内变体(第二条解析路径缺失防御、提示词与校验器漂移)见 `.trellis/spec/backend/agent-model-contracts.md`。

---

## 范例(r2 落地,新契约照抄这套形态)

1. **双向金标字面量**:`WorkspaceArchiveReferenceTest` 在 backend(`backend/src/test/java/com/example/codereview/sandbox/`)与 runner(`sandbox-runner/src/test/java/com/example/reposage/sandbox/`)各一份,用**同一批字面量**钉线格式——`agent-run-42-abcdef1234567.tar`、`patch-9-<64hex>.tar`,以及完整拒绝集(**历史断链格式 `workspace://…` 永久留在拒绝集里**)。任何一侧单方面改格式,先撞碎本侧金标。
2. **编码产出必须能被解析接受**:`parseAcceptsEveryEncodedOutput` 用 `parse(encode(...))` 闭环——生产方真实产出驱动消费方校验,这正是当年缺失的那条断言。
3. **字段序快照**:`SandboxJobFieldOrderTest`(两侧各一份)用反射把 record 组件顺序 `containsExactly` 钉死——HMAC 的 canonical JSON 由 `SandboxJobSigner` **手工枚举字段**(键字典序、不走 JSON 库),record 布局变更若不同步两侧的 canonical 形式与镜像 record,签名/完整性就会静默漂移;快照让任何布局改动先在测试里炸。
4. **固定向量**:`SandboxJobSignerTest` 两侧共享同一(作业, 密钥, 签名)向量,证明镜像实现字节级兼容。
5. **消费端形状容错 + 冻结信封**:REST 侧的形态——后端 `PageResponse` 冻结形状,前端 `unwrapPage` 明确接受的形状集合并有单测(`frontend/tests/smoke.test.mjs`)。

---

## 新增/变更跨边界格式时的检查单

1. 格式有单一事实源吗?同一部署物内 → 一个编解码器类;跨部署物 → 同构镜像 + 金标测试(ADR 0001 决策 2,不为此建共享构件)。
2. 两侧测试用的是**同一批字面量**吗?(不是"语义等价"的两批)
3. 有 `parse(encode(...))` 或"生产方真实序列化结果喂给消费方反序列化"的断言吗?
4. 旧格式/危险输入进拒绝集了吗?(历史事故格式永不删除)
5. 字段顺序参与签名/哈希吗?参与就加字段序快照测试。
6. 变更是两侧同批提交吗?单侧合入 = 生产漂移窗口。
