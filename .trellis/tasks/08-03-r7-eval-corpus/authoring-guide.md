# r7 语料创作规约(五类批次代理必读;并行模式)

权威链:prd.md(R1 配比/硬约束)→ design.md(D2 格式/D3 口径/D5 构成)→ research/expansion-readiness.md §2(素材出处)→ 本文(操作协议)。

## 并行协议(消灭共享写点)

1. **manifest.json 一律不改**。每批把自己的 cases[] 条目写到 `.trellis/tasks/08-03-r7-eval-corpus/manifest-fragments/<前缀>.json`(合法 JSON 数组,元素 = manifest case 对象),主会话统一合并。
2. 只准写:`evaluation/cases/<你的前缀>-*/**` + 你的 fragment 文件。**demo-repos/ 零写入(复制改编到你的 case 目录);evaluation/ 其余内容、backend/、原 6 例目录零接触。**
3. **不跑任何构建/测试/运行时调用**(校验统一在主会话终局做;宿主也没有 java/node)。
4. 你负责的类别、数量、split 配额、case id 前缀见派单;不足数或素材不成立时如实报告,不硬凑。

## 用例格式(design D2)

```
evaluation/cases/<id>/
├── base/   # 缺陷引入前的完整小文件树(1-8 文件)
├── head/   # 缺陷引入后(диff = head-base;误报专项 head 为正常改动)
└── knowledge/*.md   # 仅业务规则类必备(裁剪版判据文档,单文件 ≤2000 字符)
```

- base→head 必须是**可讲清楚的一次改动**(新增功能/重构/修复样子),缺陷藏在改动里;diff 行数:常规类 30-150 行,漏报专项 8k-18k 字符且缺陷完整落于单文件(DiffSplitter 20k/片约束)。
- 文件树自洽可读(import/包名一致),但**不要求可编译**——评测对象是审查而非构建(java-broken-build 先例);仍须像真实代码。

## fragment 条目 schema

```json
{
  "id": "<前缀>-<slug>",
  "split": "development|holdout",
  "language": "JAVA|PYTHON|TYPESCRIPT|JAVASCRIPT",
  "fixture": "cases/<id>",
  "fixtureLayout": "base-head",
  "expectedFindings": [{
    "category": "<七枚举优先:NULL_POINTER|SQL_INJECTION|AUTH_RISK|TRANSACTION_RISK|PERFORMANCE_RISK|BUSINESS_RULE_RISK|UNKNOWN;不合再自由串>",
    "severity": "HIGH|MEDIUM|LOW",
    "path": "head 树内相对路径(不含 head/ 前缀,即审查视角路径)",
    "line": <head 内缺陷起行>,
    "lineEnd": <止行,单行则等于 line>,
    "categoryEquivalents": ["<模型可能报的等价类别,含理由不明显时的 UNKNOWN>"]
  }],
  "nonFindings": ["<本例明确不许报的干扰点,0-2 条>"],
  "expectedPatch": null
}
```

- **行号以 head/ 文件实际行为准,写完必须逐条自查对行**(判分按行区间交集,标错=白做)。
- 误报专项 expectedFindings 为空数组,nonFindings 写清最像缺陷的干扰点;漏报专项恰 1 条预期。
- 标注最小集:模糊的"最好也报"不进标注(prd 硬约束)。

## 脱敏纪律(research §2 末节)

假密钥形如假值(`sk-demo-0000…`);包名/域名换虚构(`com.acme.*`/`example.internal`);不带真实姓名邮箱路径;demo-repos 事故编号换新前缀(EVAL-);知识文档裁剪后不含原对照表叙事。

## 质量底线

每例在 fragment 旁附一行创作说明(写进你批次的 `manifest-fragments/<前缀>-notes.md`):素材出处(答案键编号/提交/audit 条目)+ 缺陷一句话 + 为何该 severity。这是抽查复算的底稿。
