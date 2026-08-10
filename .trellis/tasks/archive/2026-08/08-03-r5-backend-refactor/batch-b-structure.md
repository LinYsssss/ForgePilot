# 批B 结构普查与处置（超长类/方法）

> 口径:类以物理行计(`wc -l`,>500 筛查);方法以**体行数**计(方法 `{` 与配对 `}` 之间的行数,
> 含空行与注释行,>60 筛查)。方法扫描为 `scripts/scan-structure.py` 的括号深度扫描
> (剥离注释/字符串/文本块后计数)。精度限制已在脚本头注明:多行签名按 `{` 所在行登记、
> switch 带 `{}` 的 arm 计入内层深度不影响方法计数、`->` 过滤丢弃 lambda 体。
> 该口径前后一致,数字可比。筛查线是筛查线,不是硬指标——处置按 design.md 深模块原则逐项判断。

## BEFORE(2026-08-10,批A 完成后)

扫描范围 `backend/src/main/java`:346 个文件,1612 个方法。

### 类 >500 行:1 个

| 行数 | 类 |
|---|---|
| 512 | `git/GitCliService.java` |

### 方法 >60 体行:9 个

| 体行 | 位置 | 方法 |
|---|---|---|
| 138 | `agent/orchestration/steps/ExecutingToolsStepExecutor.java:96` | `execute` |
| 95 | `agent/orchestration/steps/PlanningStepExecutor.java:113` | `execute` |
| 83 | `agent/plan/ReviewPlanValidator.java:72` | `validate` |
| 80 | `agent/orchestration/steps/ValidatingPatchStepExecutor.java:70` | `execute` |
| 78 | `agent/orchestration/AgentPublicationService.java:78` | `publish` |
| 73 | `ai/MockAiReviewClient.java:14` | `review` |
| 72 | `agent/orchestration/steps/PreparingRepositoryStepExecutor.java:71` | `execute` |
| 72 | `agent/model/StructuredAgentModelService.java:56` | `generateInternal`(多行签名,`{` 在 56 行) |
| 63 | `scm/github/GitHubWebhookController.java:78` | `receive` |

## 逐项处置

判断标准(design.md):拆分要找职责边界,拆完调用方应更少知道细节;把大类切成互相调用的
小类是把复杂度从类内搬到类间——不做。允许诚实保留。

### 1. GitCliService(512 行)→ 拆分

天然缝:**进程执行管道 vs git 语义**。`run()`/`OutputDrain`/`createAskPass()`/`sanitize()`/
`gitCommand()`/`addSafeDirectoryOptions()`/`askPassUsername()`/`shouldUseToken()` 及其常量
是一整块"以子进程方式执行 git 命令:命令组装(safe.directory)、凭据注入(askpass)、超时、
限量抽干、失败输出脱敏"的职责,对外只有一个方法 `run(dir, repo, args...)`——窄接口深实现,
教科书缝。抽成同包包级私有类 `GitCommandRunner`(非 Spring Bean,由 GitCliService 构造器
new,不进扫描范围);GitCliService 保留全部公共 API(类名/包/签名零改动)与 git 语义
(克隆生命周期、归档打包、log/diff 解析、工作副本管理)。拆后调用方(GitCliService)不再
知道 askpass 脚本、管道抽干、脱敏细节。归档打包(`archiveLocked`)不单独拆:它与仓库锁、
克隆生命周期共享状态,拆出去要把锁协议暴露成类间协议,违反深模块原则。

覆盖:`GitCliServiceProcessTest` 经公共 API 锁死抽干防死锁/失败脱敏路径,`sanitize` 有直接
单测(拆分后测试引用同包内改指 `GitCommandRunner.sanitize`,断言零改动);`normalizeRemote`
留在 GitCliService,`GitCliServiceTest` 零改动。**askpass/凭据分支离线不可达(需 http 远端),
无任何现有覆盖**——按特征测试策略先补 `characterization/` 锁现状(经公共 API:http 不可解析
域名 + token → 解密被查询、失败信息脱敏、askpass 临时脚本清理、不挂起),对未改动代码先跑绿,
再做逐字搬移。

### 2. ExecutingToolsStepExecutor.execute(138 体行)→ 拆分

三个真实概念挤在一个方法里:(a) 按策略执行已验证计划的工具(请求组装+盲目项预检+循环
执行+违规拒绝);(b) 终稿回执的模型交互(提示词+回执预算策略+生成校验+计划落库);
(c) 步骤推进。拆私有助手 `runPlannedTools`(带小记录 ToolRun)与 `finalizeReceipt`
(回执模型交互),回执提示词契约(带 run11-13 实证注释群)单独成 `receiptPrompt`——
它本身就是一份被注释documented的契约文本。执行顺序、异常类型与抛出时机逐字保持。
覆盖:`PlanningAndToolStepExecutorTest` 三个用例深断言提示词内容、权威参数注入、
回执预算=原计划条数、clamp 关闭——正是这些缝的行为锁。

### 3. PlanningStepExecutor.execute(95 体行)→ 拆分

两个可剥离概念:规划提示词组装(带 run11/run12 实证注释,纯输入→PromptEnvelope)与
"已验证计划+规划检查点落库并推进"。拆 `planningPrompt` 与 `persistPlanAndAdvance` 两个
私有助手。覆盖:`PlanningAndToolStepExecutorTest.planningUsesStructuredModelAndPersistsRequestIds`
深断言提示词数字同源、policy clamp、落库内容。

### 4. ReviewPlanValidator.validate(83 体行)→ 拆分

代码注释自己就把该方法描述为两阶段("内在缺陷……先于预算判断收集,使裁剪只作用于
'内在合法但超预算'的条目"),但实现靠 `errorsBefore = errors.size()` 计数技巧在共享列表上
推断"本条目是否内在合法"——隐式耦合。抽私有 `intrinsicIssues(item, …)` 返回该条目自身的
错误列表,`intrinsicallyValid = 列表为空` 变成显式事实,错误追加顺序逐字保持。这不是搬行数,
是让代码结构与其文档化概念一致。覆盖:`ReviewPlanValidatorTest` 8 个用例锁死全部错误分支
与 clamp 语义。

### 5. ValidatingPatchStepExecutor.execute(80 体行)→ 保留

线性"降级或推进"管道:四个 early-return 降级出口(补丁过期/无钉死命令/归档不可用/沙箱
不可用)各自带设计注释,中段(归档产出+验证循环)若外拆,必须把"要么降级原因要么验证后
候选"做成 Either 式跨方法返回协议——调用方要知道的更多而不是更少,正是把复杂度从类内搬到
类间。`commands()`/`publish()` 已是独立助手,方法内每个阶段 5–15 行。写实:80 > 60,保留。

### 6. AgentPublicationService.publish(78 体行,@Transactional)→ 拆分

一个真实缝:"远端投递+失败分类映射"(publisher 解析、try/catch 把底层失败映射为
RETRYABLE/PERMANENT 带定界原因)是带自身注释的完整子职责,抽私有 `deliverToRemote`。
`@Transactional` 留在 `publish` 入口,新助手仅被事务方法内部调用,不引入自调用失效面
(核对:本类无其他 @Transactional 方法,publish 无同类调用方)。无凭证演示姿态分支保留
在主方法(early-return record,外拆反而要传播返回协议)。覆盖:
`AgentPublicationServiceTest` 4 个用例锁幂等、跳过姿态、PERMANENT/RETRYABLE 映射与定界原因。

### 7. MockAiReviewClient.review(73 体行)→ 保留

演示 mock 的启发式规则表:四个独立 if 块各自是一次 11 参构造调用(数据重、逻辑轻),
无隐藏细节可封装——拆成四个私有方法后每个只剩一条构造语句,调用方知道的一点没变少。
design.md 的"线性长方法优于三段碎片"条款的标准案例。写实:73 > 60,保留。

### 8. StructuredAgentModelService.generateInternal(72 体行)→ 拆分

方法体近四成是一个 28 行内联 lambda(JSON 修复回调),靠两个 AtomicReference 侧信道回传
修复调用/响应——validator 的 `Function<String,String>` 回调 API 决定了侧信道存在,但 lambda 体
(失败标记→审计→repairJson→REPAIR 落库→异常审计)可抽成私有 `attemptRepair`,lambda 缩为
一行委托。审计写入序列逐字保持。覆盖:`StructuredAgentModelServiceTest` 锁成功/修复/供应商
失败三条审计轨迹(含 REPAIR 独立 token 记账)。

### 9. PreparingRepositoryStepExecutor.execute(72 体行)→ 拆分

方法已有三个私有助手(resolveBaseHead/output/changedPaths),剩余主体中"经沙箱工具读回
预置 diff"(入参构造+maxBytes 收敛+工具执行+成败判定+输出提取)是 F-04 注释里点名的
完整概念("一来一回同时验证引用契约与共享卷挂载"),抽私有 `readBackPreparedDiff`。
覆盖:`PreparingRepositoryStepExecutorTest` 两用例深断言工具入参(archiveRef 钉线格式、
无 command/shell 注入面)与档案产出坐标。

### 10. GitHubWebhookController.receive(63 体行)→ 保留

超线 3 行。方法就是类 Javadoc 声明的安全时序契约本身:验签先于解析、落库后于验签、
原子幂等——顺序即边界,每步 3–8 行已最小化,外拆助手只会把强制时序打散到方法间。
spec 的"controller 一行委托"规则针对 CRUD 领域控制器;webhook 验签是边界层逻辑,整体
迁往 service 属于批C 级别的边界改动,不在批B(低风险、签名不动)范围。写实:63 > 60,保留。

## 命名对齐(批B 范围:仅包内私有命名)

r4 规范(directory-structure.md / quality-guidelines.md)对命名的显式规则:领域五件套
命名、DTO 嵌套 record、`<State>StepExecutor` 一态一类、测试镜像包结构。逐项核对本批触碰
文件,无任何包级私有名被规范点名为错;按任务规则不主动猎取改名。**本批改名:0。**
新增协作类 `GitCommandRunner` 为包级私有,命名与 `git/`(git CLI 边界)域语义一致。

## AFTER(2026-08-10,同一脚本 `scripts/scan-structure.py` 回填)

扫描范围不变:347 个文件(+1:新增 `GitCommandRunner`),1623 个方法(+11:抽出的私有助手)。

### 类 >500 行:1 → 0

| 前 | 后 | 类 |
|---|---|---|
| 512 | 328 | `git/GitCliService.java`(git 语义层) |
| — | 221 | `git/GitCommandRunner.java`(新,包级私有,进程执行边界) |

### 方法 >60 体行:9 → 3(全部为留有书面理由的保留项,数字不变,写实)

| 前 | 后 | 位置 | 处置 |
|---|---|---|---|
| 138 | 48 | ExecutingToolsStepExecutor.execute | 拆分:`runPlannedTools` / `finalizeReceipt` / `receiptPrompt` |
| 95 | 31 | PlanningStepExecutor.execute | 拆分:`planningPrompt` / `persistPlanAndAdvance` |
| 83 | 57 | ReviewPlanValidator.validate | 拆分:`intrinsicIssues`(消除 errorsBefore 计数技巧) |
| 80 | 80 | ValidatingPatchStepExecutor.execute | **保留**(处置表第 5 项理由) |
| 78 | 55 | AgentPublicationService.publish | 拆分:`deliverToRemote`(@Transactional 留在 publish 入口) |
| 73 | 73 | MockAiReviewClient.review | **保留**(处置表第 7 项理由) |
| 72 | 46 | StructuredAgentModelService.generateInternal | 拆分:`attemptRepair`(内联 lambda 具名化) |
| 72 | 50 | PreparingRepositoryStepExecutor.execute | 拆分:`readBackPreparedDiff` |
| 63 | 63 | GitHubWebhookController.receive | **保留**(处置表第 10 项理由) |

### 特征测试

新增 `backend/src/test/java/com/example/codereview/characterization/GitCredentialFlowCharacterizationTest.java`
(锁 askpass 凭据路径:解密被查询、失败脱敏、脚本清理、不挂起;重构前对未改动代码跑绿
3.0s,重构后原样通过)。存量测试仅 `GitCliServiceProcessTest` 的 2 处 `sanitize` 静态引用
同包内改指 `GitCommandRunner`,断言零改动;其余测试文件零触碰。

### 验证

- 容器化 `mvn -s .mvn/settings.xml -B clean verify`:**BUILD SUCCESS**
  `Tests run: 576, Failures: 0, Errors: 0, Skipped: 3`(575 存量 + 1 新特征测试,全绿)。
- 契约复核:diff 面为 7 个主代码文件 + 1 个测试文件 + 3 个新增文件;
  `backend/src/main/resources`(迁移/yml)、`common/`(冻结契约)、`pom.xml` 零改动,
  diff 中无任何 REST 注解/DTO record 行变更;`GitHubWebhookController` 未触碰。
- @Transactional 自调用核查:批内唯一事务方法 `AgentPublicationService.publish` 保持事务
  入口不动,新助手仅为其方法内部私有调用,不产生自调用失效面。
