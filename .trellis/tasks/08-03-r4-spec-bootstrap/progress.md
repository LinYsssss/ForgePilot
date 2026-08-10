# Progress — 08-03-r4-spec-bootstrap

> 2026-08-09 执行完毕(trellis-spec-bootstrap 单主工作流:盘点现状 → 逐包读源码取证 → 写规范 → 反占位符/锚点核验)。

## 各包变更清单

### backend(`.trellis/spec/backend/`,7 → 10 文件,全部实证化)

| 文件 | 动作 | 内容 |
|---|---|---|
| index.md | 重写 | 真实导航表,删除 "To fill" 状态列与填表教程 boilerplate |
| directory-structure.md | 模板→实写 | 领域包平铺(project/ 五件套范例)、agent 子包地图、依赖方向(common 不反向依赖)、DTO 嵌套 record、配置按所有权拆文件(app-agent/app-boundary 非 optional import)、测试镜像包结构 |
| database-guidelines.md | 模板→实写 | Flyway 三律(历史迁移零改动、接实测最大号 V27 之后、V22–V25 预留)、V26 迁移注释范式、事务边界(`@Transactional` 不自调用→独立 bean `ReviewTaskStatusService`/`AiCallLogService` 或 `TransactionTemplate` 见 `AgentRecoveryService`)、outbox 一致性 |
| error-handling.md | 补全 | 保留 r2 两条 Required 规则原文(no-blind-errors、瞬态→RETRYABLE 映射);补 Overview/Error Types(ErrorCode/BusinessException/AgentFailureType)、API 信封(handleBusiness 不重推导 legacy 状态的事故规则)、常见错误;清除全部模板注释 |
| logging-guidelines.md | 模板→实写 | traceId 四环节全链路(TraceIdFilter→ApiResponse→AgentStepMessage→AgentStepConsumer save/restore)、级别约定(WARN=降级留痕/ERROR=运维介入,均引真实调用点)、禁入项(AgentPromptAssembler.redact 的脱敏面、2000 字符有界原因) |
| quality-guidelines.md | 补全 | 保留 F-02 tmpdir 规则原文;补容器化测试命令、授权矩阵冻结准入(陌生人永不 2xx/匿名恒 401)、surefire 三层属性优先级、按影响面的评审清单(全部内链到专门规范) |
| security-guidelines.md | 保留 | r2/r1 实证内容(SPA CSRF、trivy 供应链门禁),未动 |
| agent-model-contracts.md | 保留 | r2 实证内容(两级防御、共享防御单源、posture 降级),未动 |
| **frozen-contracts.md** | **新增** | 冻结契约常驻化:ErrorCode(名/legacyCode 不可改,可增)、PageResponse 形状+unwrapPage 消费锚点、ApiResponse 五字段、ProjectAuthorization 方法面+404/403 语义+无管理员旁路+矩阵准入、Flyway 不可变、REST 路径字段(适配器先例)、MQ 载荷(AgentStepMessage、SandboxJob 同构镜像+字段序快照、WorkspaceArchiveReference 线格式)、backend↔model-service /predict 契约 |
| **prompt-management.md** | **新增** | R3 五规则全部成文:宁精勿多(单一职责、清单≤10、新条目须附漏报案例)、漏报 recall-first(初审多报/复核压误报/确定性兜底,禁以召回换误报好看)、版本化与评测门禁(变更附评测对比、漏报率不升、promptHash→版本→数字可互查)、退役机制(N=3 轮无捕获→候选)、禁承诺红线(禁"零漏报",唯一口径"漏报率实测持续下降+多层兜底")。现状锚点:PromptTemplateRegistry/AgentPromptAssembler/V16 prompt_hash、四个步骤执行器的内联拼接待 r8 清零、manifest 与注册表版本号未统一的事实 |

### frontend(`.trellis/spec/frontend/`,6 → 5 文件)

| 文件 | 动作 | 内容 |
|---|---|---|
| index.md | 重写 | 真实导航表 |
| directory-structure.md | 保留+微修 | 补 `directives/`(listNav.js)进目录树,其余未动 |
| state-management.md | 保留 | 未动 |
| component-guidelines.md | 模板→实写 | SFC 形态(defineProps 工厂默认值/defineEmits/update:modelValue)、展示组件边界、**决策逻辑抽纯 JS 文件**(patchApprovalPolicy.js 先例,node runner 不渲染组件)、Observatory 样式单源(21 个 SFC 零 `<style>` 块、sev-* 类名=后端枚举跨层锚点、reduced-motion)、交互先例(details/summary、listNav、hash query 深链) |
| quality-guidelines.md | 补全 | 保留 r3 lockfile 源纪律原文;补 API 边界容错(unwrapPage/ApiError/401 单点漏斗)、测试两层写法(smoke 源码断言+composables 行为测试的"桩先于 import"规则)、评审清单 |
| hook-guidelines.md | **删除** | React hooks 模板,不适用;composable 规则归 state-management.md |
| type-safety.md | **删除** | 纯 JS 无 TS/校验库,不适用;边界容错规则并入 quality-guidelines.md |

### sandbox-runner(**新建** `.trellis/spec/sandbox-runner/`,2 文件)

- index.md + guidelines.md:依赖极简与同构镜像原则(ADR 0001 决策 2/3)、入站信任链固定序列(验签→过期→重放→执行,fail-fast secret)、容器加固不变量(ContainerPolicy 八项一条不许删、SandboxCommandCatalog 白名单)、路径圈禁范式(语法归 codec、resolver 双重圈禁,F-03 注释即事故记录)、配置、测试写法(纯 JUnit+注入 Clock,契约三件套)。

### model-service(**新建** `.trellis/spec/model-service/`,2 文件)

- index.md + guidelines.md:单模块布局、env 模块常量⇒importlib.reload 测试范式(load_app 夹具)、安全姿态三条(reload 默认 404 因 joblib=pickle、pydantic 有界输入 422、错误只回分类不回路径/异常,字段集合被测试钉死)、trained-model→fallback-rules 降级链(source 如实标注、规则兜底永不移除)、camelCase 契约与钉版依赖、容器化测试命令。

### guides(`.trellis/spec/guides/`,3 → 5 文件)

- **contract-testing.md 新增**(R2):跨进程/跨模块格式必须"生产方真实产出驱动消费方"双向契约测试,禁止两侧手造数据自测;F-03 事故记录;r2 范例五件(双向金标字面量 `agent-run-42-…`、`parse(encode(...))` 闭环、字段序快照、固定向量 HMAC、消费端形状容错);6 条检查单。
- **demo-assets-and-claims.md 新增**(R4):demo-repos 43 条故意缺陷保留义务+patch 防篡改流程、README 诚实边界七条只增不删、能力表述须指向已存在的实测产物。
- index.md 更新:两个新 guide 进表格+触发清单;两个 thinking guide 保留未动。

## 验证结果

```
$ python3 ./.trellis/scripts/get_context.py --mode packages
Single-repo project (no packages configured)

Spec layers: backend, frontend, model-service, sandbox-runner

$ ls .trellis/spec/
backend  frontend  guides  model-service  sandbox-runner
```

- 占位符扫描 `grep -Rn "To be filled|TODO|To fill|placeholder" .trellis/spec/` → 零命中(唯一残留 `<!--` 是 security-guidelines.md 代码块内的真实 XML/CVE 注释示例)。
- 全部 markdown 内链 + 约 50 个被引真实代码/文档锚点逐一存在性核验通过(含两侧契约测试、迁移文件、prompt 资源、demo-repos 文档)。
- 发现机制说明:项目为 single-repo 模式(config.yaml 未配 packages),`packages_context._scan_spec_layers` 直接扫 `.trellis/spec/` 子目录(排除 guides),**新目录零配置即被发现**;刻意不改成 monorepo packages 配置——那会要求 `spec/<pkg>/<layer>/` 二级结构并触发 session-start 的 legacy 结构告警,对本仓库是净伤害。

## 关键决策

1. **r2/r3 实证规范一字未动**,新内容以内链环绕(frozen-contracts/quality 引 agent-model-contracts 与 security),避免复述漂移。
2. **sandbox-runner / model-service 各只给 index+guidelines 两文件**——包小(35 类/1 模块),按"宁缺毋滥"不摊薄成多文件。
3. **契约测试纪律放 guides/**(跨 backend/sandbox-runner/frontend/model-service 四包,无单一属主,且 guides 在上下文注入中"always included");prompt 治理放 backend(prompt 代码属主)。
4. 语言:新内容中文(与 PRD/ADR/近期 r3 条目一致),保留的英文规范维持原文。
