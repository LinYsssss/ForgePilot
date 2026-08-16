# ForgePilot 智能研发质量平台最终方案文档

**版本：V1.0 / 方案冻结版**  
**产品名称：ForgePilot**  
**毕业设计题目建议：基于大模型的智能研发质量平台设计与实现**

> **ForgePilot — Navigate requirements. Guard every change.**  
> 从需求理解到代码合并，辅助研发决策并守住交付质量。

---

## 1. 项目最终定位

ForgePilot 不再定位为单纯的“AI 代码审查工具”，也不做 Jira、GitLab、Jenkins、HR 等系统的大而全复制品。

最终定位：

> **面向软件研发流程的智能研发质量平台。**

围绕一条核心业务链展开：

```text
需求
 ↓
需求质量检查
 ↓
任务分配
 ↓
研发辅助
 ↓
代码实现
 ↓
Pull Request
 ↓
自动智能审查
 ↓
缺陷发现
 ↓
责任指派
 ↓
修复与复审
 ↓
质量门禁
 ↓
质量度量
```

系统核心问题不是“AI 能不能看代码”，而是：

> **如何利用需求、验收条件、项目知识和代码变更等研发上下文，让大模型更准确地判断代码是否正确实现需求，并建立可信、可追踪的研发质量闭环。**

---

## 2. ForgePilot 的核心特色

### 2.1 研发上下文

AI 不再只读取 Git Diff，而是理解：

```text
Requirement
+
Acceptance Criteria
+
Project Knowledge
+
Code Context
+
Git Diff
+
Historical Findings
```

### 2.2 智能质量

| 阶段 | 能力 | 核心问题 |
|---|---|---|
| 开发前 | 需求质量检查 | 需求写清楚了吗？ |
| 开发中 | 需求实现助手 | 这个需求应该如何在当前项目实现？ |
| 开发后 | PR 智能审查 | 代码实现正确吗？有没有引入问题？ |

### 2.3 质量闭环

```text
发现
→ 确认
→ 指派
→ 修复
→ Fix Commit
→ 自动复审
→ 验证
→ 关闭
```

---

## 3. 最终用户角色

| 角色 | 职责 |
|---|---|
| 系统管理员 | 用户及系统配置 |
| 项目负责人 | 项目、成员、需求、质量规则 |
| 开发人员 | 接收需求、开发、处理 Finding |
| 审查人员 | PR 审查、Finding 确认 |
| 验证人员 | 修复验证、质量确认 |
| 只读成员 | 查看项目数据 |

明确不做：

- 组织架构
- 部门
- 人事档案
- 考勤
- 绩效
- 工资
- 请假
- 工时

---

## 4. 最终一级功能结构

```text
ForgePilot
│
├─ 工作台
├─ 项目
├─ 研发任务
├─ 代码仓库
├─ 智能审查
├─ 质量中心
├─ 知识库
└─ 研发度量
```

研发助手不单独占一级菜单，而应嵌入需求详情页。

---

## 5. 工作台

主要展示：

- 我的研发任务
- 待我处理的 Finding
- 待我审查的 PR
- 待验证的问题
- 当前项目风险
- 最近审查结果
- 需求完成情况
- 高风险问题数量

---

## 6. 项目与成员管理

支持：

- 添加成员
- 删除成员
- 设置角色
- 设置项目负责人
- Git 仓库配置
- 默认分支配置
- 知识库配置
- 质量规则配置
- Webhook 配置

不做复杂组织系统。

---

## 7. 研发需求模块

Requirement 建议字段：

```text
需求编号
需求标题
业务背景
需求描述
优先级
负责人
开发人员
状态
所属项目
```

Acceptance Criteria 示例：

```text
REQ-1024
订单取消库存释放

AC1
订单取消成功后必须释放库存

AC2
库存服务失败时进入重试机制

AC3
重复取消不得重复释放库存
```

---

## 8. 需求生命周期

```text
DRAFT
草稿
 ↓
NEEDS_IMPROVEMENT
待完善
 ↓
READY
可开发
 ↓
IN_DEVELOPMENT
开发中
 ↓
IN_REVIEW
审查中
 ↓
DONE
完成
```

可补充：

```text
CANCELED
已取消
```

---

## 9. 需求质量检查

正式名称：

> **需求质量检查**

UI 可使用：

> **需求体检**

检查六个维度：

1. 完整性
2. 明确性
3. 可测试性
4. 异常场景覆盖
5. 项目规则冲突
6. 研发风险提示

采用：

> **确定性规则 + 项目知识 + LLM**

流程：

```text
Requirement
+
Acceptance Criteria
        ↓
字段与规则检查
        ↓
Knowledge Retrieval
        ↓
LLM 结构化分析
        ↓
Schema Validation
        ↓
Requirement Quality Report
```

---

## 10. 需求与代码追踪

建立：

```text
Requirement
     ↓
Branch
     ↓
Commit
     ↓
Pull Request
     ↓
Review
     ↓
Finding
     ↓
Fix Commit
```

系统可回答：

- 这个需求修改了哪些代码？
- 这个 PR 实现了什么需求？
- 这个 Finding 来源于哪个需求？
- 这个问题最终由哪个 Commit 修复？

---

## 11. Pull Request 成为核心审查入口

主流程：

```text
开发人员 Push
      ↓
Pull Request 更新
      ↓
Webhook
      ↓
ForgePilot 自动审查
```

手动 Commit 审查保留，但降级为辅助功能：

```text
代码仓库
→ Commit
→ 更多操作
→ 临时审查
```

用于历史 Commit、无 PR 场景、调试和演示。

---

## 12. 研发上下文构建器

建议命名：

> **Development Context Builder**

统一构建：

```text
Requirement
+
Acceptance Criteria
+
Project Knowledge
+
Relevant Code
+
Git Diff
+
Historical Quality Context
```

供以下能力共同使用：

- 需求检查
- 研发助手
- PR 审查

---

## 13. 研发助手

正式定位：

> **基于当前需求和项目上下文的需求实现助手**

支持：

- 项目代码理解
- 需求影响分析
- 项目规则查询
- 实现方案辅助
- 研发风险提示

上下文包括：

```text
当前项目
当前需求
验收条件
项目知识库
代码仓库
相关代码
关联 PR
```

明确不做：

- 万能聊天机器人
- 自动完成整个开发任务
- 自动 Commit
- 自动 Push
- 替代 Codex / Claude Code

---

## 14. PR 智能审查

分为两个层次。

### 14.1 需求一致性审查

回答：

> **代码有没有正确实现需求？**

示例：

```text
AC1 库存释放
✅ 已覆盖

AC2 失败重试
❌ 未发现实现

AC3 幂等保护
⚠ 存在风险
```

### 14.2 代码质量审查

检查：

- 业务逻辑
- 安全问题
- 异常处理
- 空指针
- SQL
- 并发
- 事务
- 权限
- 可靠性

最终输入：

```text
需求
+
验收条件
+
项目知识
+
代码 Diff
```

---

## 15. Finding 质量中心

Finding 包含：

```text
标题
问题类型
严重等级
代码文件
代码位置
问题描述
证据
关联需求
关联 PR
责任人
状态
修复 Commit
验证结果
```

生命周期：

```text
OPEN
 ↓
CONFIRMED
 ↓
IN_PROGRESS
 ↓
FIXED
 ↓
VERIFIED
 ↓
CLOSED
```

允许：

```text
REJECTED
误报
```

---

## 16. 缺陷处理闭环

```text
AI Finding
 ↓
人工确认
 ↓
指派开发人员
 ↓
开发修复
 ↓
关联 Fix Commit
 ↓
PR 更新
 ↓
自动复审
 ↓
Verifier 验证
 ↓
人工确认
 ↓
关闭
```

---

## 17. 质量门禁

综合：

- 需求一致性
- 高风险 Findings
- Finding 可信度
- 验证结果
- 修复状态
- 项目规则

最终状态：

```text
PASS
WARN
BLOCK
```

---

## 18. 知识库重新定位

保留：

- 文档上传
- 文档删除
- 分类
- 查看
- 索引
- 项目规则管理

砍掉独立知识库聊天。

知识库作为 ForgePilot AI 能力的后台知识源，用于：

- 需求体检
- 研发助手
- PR 审查
- Finding 验证

---

## 19. 研发度量

### 研发质量

- PR 数量
- 自动审查覆盖率
- Finding 数量
- 高风险 Finding
- 有效 Finding
- 误报数量
- 问题关闭率

### 需求质量

- 需求数量
- Ready 率
- 平均质量问题数
- 需求一致性问题数

### 处理效率

- 平均问题处理时间
- 平均修复时间
- 平均审查耗时

### AI 指标

- Token 消耗
- 平均模型调用时间
- 调用成功率
- 单次审查平均成本

---

## 20. Risk Model 最终处理

### 决定：正式砍掉

删除正式运行链路中的：

```text
model-service
ModelRiskClient
HttpModelRiskClient
NoopModelRiskClient
ModelRiskSignal
相关配置
相关 Docker 部署
```

原因：

1. 与核心 LLM Review 重复
2. 不利用 Requirement Context
3. 需要维护独立 Python 服务
4. 尚未证明带来足够效果提升
5. 分散论文核心

Git 历史可保留，论文实验如有需要可作为历史对照方案。

---

## 21. RAG 最终处理

保留，但后台化。

产品只展示：

> 项目知识库 / 项目知识增强

内部可继续支持：

- Full Context
- Embedding
- Vector Search
- pgvector

RAG 是实现手段，不是产品功能。

---

## 22. Agent 最终处理

保留能力，弱化术语。

用户看到：

- 自动审查
- 自动验证
- 自动复审
- 质量门禁

Agent 属于内部实现。

---

## 23. Sandbox 最终处理

保留，用于 AI Finding 的安全取证与验证。

不增加“沙箱管理”一级菜单。

---

## 24. RabbitMQ + Outbox

继续保留，用于：

- PR 自动任务
- 异步 AI 审查
- 重试
- 消息可靠性

完全后台化。

---

## 25. 最终技术架构

```text
                 ForgePilot Frontend
                       │
                     Vue 3
                       │
              ─────────────────
                       │
                Spring Boot
                       │
       ┌───────────────┼────────────────┐
       │               │                │
 Requirement       Review Engine    Quality Flow
       │               │                │
       └────── Context Builder ──────────┘
                       │
             ┌─────────┴─────────┐
             │                   │
        Project Knowledge       LLM
             │                   │
             └─────────┬─────────┘
                       ↓
                 Structured Result
                       ↓
              Evidence / Verification
                       ↓
                  Quality Gate
```

基础设施：

- PostgreSQL
- RabbitMQ
- Docker Sandbox
- Prometheus / Metrics

明确：

- 不增加 Redis
- 不增加 Kafka
- 不增加 Elasticsearch
- 不再拆新的微服务

---

## 26. 明确砍掉的功能

以下进入正式 **NON-GOALS**：

- 完整 Jira / 禅道
- Sprint
- Story Point
- 甘特图
- 工时管理
- HR 管理
- 部门组织架构
- 考勤绩效
- 完整测试管理平台
- Jenkins 式 CI/CD 设计器
- 制品仓库
- 发布编排
- 通用 AI 聊天
- 独立知识库 Chat
- AI 自动写完整需求
- AI 全自动开发
- AI 自动提交代码
- Risk Model
- 为凑技术增加中间件
- 无实验依据的新 Agent
- Knowledge 自动生长等非必要增强

---

## 27. 毕业设计最终功能模块

1. 用户认证与权限管理  
2. 项目与成员管理  
3. 研发需求管理  
4. 需求验收条件管理  
5. 需求质量检查  
6. 研发任务分配与状态管理  
7. Git 仓库与代码版本管理  
8. 需求与代码变更追踪  
9. Pull Request 管理  
10. 项目知识库管理  
11. 智能代码审查  
12. 需求一致性分析  
13. 缺陷发现与责任指派  
14. 缺陷修复验证与质量门禁  
15. 研发质量度量与 AI 调用监控  

---

## 28. 毕业设计核心技术

技术主线：

> **大语言模型 + 研发上下文增强 + 项目知识增强 + Git Diff 分析 + 证据验证 + 异步任务 + 安全沙箱 + 质量门禁**

核心方法：

> **研发上下文增强智能审查**

```text
Requirement
+
Acceptance Criteria
+
Project Knowledge
+
Code Context
+
Git Diff
```

---

## 29. 毕业论文实验设计

### Baseline

```text
Diff + LLM
```

### 方案 A

```text
Diff + Project Knowledge
```

### 方案 B

```text
Diff + Requirement + Acceptance Criteria
```

### 方案 C

```text
Diff + Requirement + Acceptance Criteria + Knowledge
```

### 方案 D

```text
方案 C + Evidence Verification
```

指标：

- Precision
- Recall
- 漏报率
- 误报率
- 需求一致性问题命中率
- Token 消耗
- 审查耗时

---

## 30. 最终开发路线

### Phase 0：架构清理与品牌冻结

- 产品改名 ForgePilot
- Risk Model 下线
- model-service 移出正式架构
- 统一系统术语
- 冻结产品范围

### Phase 1：研发业务骨架

- Project Member
- Requirement
- Acceptance Criteria
- Requirement Status
- Assignee

### Phase 2：需求质量

- 需求质量规则
- 项目知识增强
- LLM 结构化需求检查
- 质量报告

### Phase 3：需求代码关联

```text
Requirement
↔ Branch
↔ Commit
↔ PR
```

### Phase 4：上下文增强 PR 审查

```text
Requirement
+
Acceptance Criteria
+
Knowledge
+
Diff
```

输出：

- Requirement Coverage
- Findings
- Evidence

### Phase 5：质量闭环

```text
Finding
→ Confirm
→ Assign
→ Fix
→ Re-review
→ Verify
→ Close
```

并接入 Quality Gate。

### Phase 6：研发助手

增加：

> Requirement-aware Development Assistant

只服务需求实现与项目理解。

### Phase 7：工作台与度量

- 我的待办
- 团队质量
- Requirement 质量
- Finding 趋势
- AI 调用指标

### Phase 8：实验与答辩

从这里开始**禁止新增业务功能**。

完成：

- Baseline
- 消融实验
- Prompt 调整
- 最终指标
- 答辩案例
- 系统截图
- 论文数据

---

## 31. 最终验收场景

创建需求：

> “订单取消库存释放”

填写：

```text
AC1：释放库存
AC2：失败重试
AC3：保证幂等
```

需求体检发现：

> 缺少库存服务异常场景定义。

人工修改后进入 READY。

项目负责人指派开发人员。

开发人员通过研发助手询问：

> 当前项目库存释放逻辑在哪里？

开发并创建 PR。

ForgePilot 自动：

```text
获取 PR Diff
↓
读取 Requirement
↓
读取 Acceptance Criteria
↓
读取项目知识
↓
执行 AI Review
```

输出：

```text
AC1
✅ 已实现

AC2
❌ 未发现失败重试

AC3
⚠ 缺乏幂等保护
```

生成 Finding：

> 库存释放失败后未进入重试流程。

指派开发人员修复。

开发提交 Fix Commit。

ForgePilot：

```text
自动重新审查
→ 验证 Finding 已解决
→ Quality Gate PASS
→ Requirement DONE
```

---

## 32. 最终品牌与论文定位

### 产品名称

# ForgePilot

含义：

- `Forge`：构建、锻造软件工程
- `Pilot`：引导、辅助研发人员完成正确决策

ForgePilot 既：

> **辅助开发**

也：

> **把控质量**

### 产品定位

> **ForgePilot 是一个面向软件研发流程的智能研发质量平台，通过融合需求、验收条件、项目知识和代码变更等研发上下文，为开发人员提供需求质量检查、需求实现辅助、Pull Request 智能审查、缺陷修复闭环和研发质量度量能力。**

### 毕业设计题目

> **基于大模型的智能研发质量平台设计与实现**

### 论文核心方法

> **研发上下文增强的智能代码审查方法**

三层关系：

```text
毕业设计
基于大模型的智能研发质量平台设计与实现

              ↓

产品
ForgePilot

              ↓

核心方法
研发上下文增强智能代码审查
```

---

## 33. 最终范围原则

从本版本开始，后续任何新功能都必须回答：

> **它是否直接服务于“需求 → 实现 → 审查 → 修复 → 质量闭环”这条主链？**

如果不能直接服务于这条主链，则默认：

> **不做。**

ForgePilot 后续迭代目标不是继续扩大功能数量，而是提高：

- 业务闭环完整度
- AI 审查有效性
- 需求与代码可追溯性
- Finding 可信度
- 修复验证能力
- 研发质量可度量性

这份方案作为 ForgePilot 后续开发、毕业设计材料与论文设计的统一范围基线。
