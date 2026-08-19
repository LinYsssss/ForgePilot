# fp 批次创作说明（误报专项 4 例）

> 形态：完全无缺陷的正常 diff，expectedFindings 全部为空数组，考"忍得住不报"。
> severity 一栏对误报专项不适用（无预期 finding），改记"最大诱饵"供抽查复算对照。

- **fp-java-whitelist-order-by**（development，JAVA）：素材 = `evaluation/cases/python-safe-parameterization` 手法推广（research §2.E.1），换 Java + 动态排序场景。无缺陷；最大诱饵是 `order by " + sortField.column()` 的 SQL 字符串拼接外观——拼接源仅为 `AuditSortField` 枚举白名单常量，请求原文只做白名单查找（未命中回落 CREATED_AT），关键字走占位符绑定且 LIKE 通配符已转义（显式 escape 子句）。
- **fp-java-guarded-admin-endpoint**（holdout，JAVA）：手工构造，安全批越权例（demo-repos M7/M8 形态）的镜像（research §2.E.1），不依赖安全批产物。无缺陷；最大诱饵是"新增管理员停用/列表接口"的越权外观——类级 `@PreAuthorize("hasRole('ADMIN')")` 覆盖全部端点，服务层按操作者租户加载目标 + 显式 tenantId 比对双保险，另有禁自我停用与审计落痕。
- **fp-python-chore-gitignore-cleanup**（development，PYTHON）：素材 = 本仓真实提交 e7d350e（chore：移除误入库编译产物并补 gitignore）脱敏改编（research §2.E.2）；二进制 .pyc 产物改编为文本 JSON 报告以获得可审查 diff，路径/模块名全部虚构。无缺陷；最大诱饵是"删除文件 + 扩大 ignore 范围"的回归外观——删除物是脚本可重建的生成物，忽略规则属常规工程卫生。
- **fp-java-payout-extract-method**（development，JAVA）：素材 = demo-repos payment-settlement-service main 分支切片（对照表 §2.2："代码基本正确（作为对照）"，提交 86d6af6）脱敏改编：`com.example.settlement` → `com.acme.payout`，结算→打款，去除 docs/事故编号引用（research §2.E.3）。head 对 PayoutService 做抽方法重构、幂等短路改用 `Optional.orElseGet`、命名统一（`netFen`→`netAmountFen`，CommissionCalculator 字段 `rates`→`rateRepository`），条件/异常信息/执行语义逐行不变。无缺陷；最大诱饵是"金额计算逻辑被搬动"的业务规则外观（P3 舍入、P4 最小净额都是 patch 里的著名缺陷，此处均为 main 的正确形态）。

split 配额：development 3 / holdout 1（fp-java-guarded-admin-endpoint 入 holdout，与安全批 development 侧的越权例形成镜像对照）。

结构自查（写作时人工核对）：四例 head−base diff 均非空、有独立叙事（新增搜索功能 / 新增管理能力 / 工程卫生 chore / 无害重构），无纯空格改动；量级分别为 79 / 83 / 38 / 31 行，均在常规类 30-150 行区间。
