# Sandbox Runner Guidelines

> 单一扁平包 `com.example.reposage.sandbox`(35 个类,不分层)。规模与结构是刻意的,先读"依赖极简"再动手。

---

## 依赖极简与同构镜像(结构性原则)

- **不为共享类型引入共享 Maven 模块。** backend 与 runner 是独立部署物、独立 pom;runner 持有 Docker socket,依赖面就是攻击面。跨模块契约类型采用**逐字节同构镜像**:`WorkspaceArchiveReference`、`SandboxJob`、`SandboxJobSigner` 在两侧各有一份,同构性不靠编译期,靠双向金标测试(决策记录:`docs/adr/0001-工作区归档引用契约的单一事实源.md` 决策 2/3)。
- 改任何镜像类 = 两侧同批修改 + 先改金标测试;单侧改动会先撞碎本侧 `WorkspaceArchiveReferenceTest` / `SandboxJobFieldOrderTest` / `SandboxJobSignerTest`(设计如此)。流程见 `.trellis/spec/guides/contract-testing.md`。
- 保持扁平单包,不引入 controller/service 分层——本包没有 Web 层(`web-application-type: none`),入口只有一个 `@RabbitListener`。

## 入站信任链(顺序不可调换、环节不可跳过)

`SandboxJobConsumer.consume` 的固定序列,新消费路径必须复刻:

1. **HMAC 验签**:`SandboxJobSigner.verify`——规范化 JSON(键字典序、无空白、手工转义,**不走 JSON 库**,防序列化差异)+ `MessageDigest.isEqual` 常时比较;
2. **过期检查**:`expiryEpochSeconds` 对 `Clock`(注入的 bean,可测);
3. **重放防护**:`SandboxReplayGuard.checkAndRecord(nonce)`;
4. 全过才执行;任何一环失败返回 `SandboxResult` `REJECTED` 并 `log.warn` 带 jobId 与拒因(不抛异常,不 requeue——配置 `default-requeue-rejected: false`)。

签名密钥 `app.sandbox.signing-secret` 在构造器 fail-fast(`requireSecret`),空值起不来,不允许"没配密钥就不验"的静默降级。

## 容器加固不变量(`ContainerPolicy`)

`docker create` 参数列表固定生成,以下项**一条都不许删**:
`--network none`、`--read-only`、`--cap-drop ALL`、`--security-opt no-new-privileges`、`--user 65534:65534`、`--cpus/--memory/--pids-limit`(取自 `job.limits`)、workspace bind mount + `/tmp` tmpfs `noexec,nosuid`。

- `commandId` 走白名单:唯一目录是 `SandboxCommandCatalog.commands()`(固定绝对路径可执行 + 固定参数,id 按语言命名空间 `java.*`/`python.*`/`javascript.*`,由 `JavaCommandCatalogTest` 等契约测试钉住),未注册命令抛 `SecurityException`;
- 镜像必须 digest 固定(`validateImageDigest`),不接受浮动 tag。

## 路径圈禁

`WorkspaceArchiveResolver.resolve` 是范式:语法规则**全部**收敛在 `WorkspaceArchiveReference.parse`(白名单、`..`、前导 `-`/`.`、长度、无 scheme——白名单本身拒绝 `:` 和 `/`),之后仍做双重圈禁(`normalize` 后 `startsWith(archiveRoot)`,`toRealPath` 后再查一次 + 常规文件检查)。**不要在 resolver 里手写第二套语法校验**——当年手写检查无条件拒绝 backend 真实产出,正是 F-03 断链(该类注释即事故记录)。归档卷在 compose 里对 runner 必须保持 `:ro`,方向不可颠倒(backend 是唯一生产者)。

## 配置

`app.sandbox.{signing-secret, work-root, dependency-cache-root, archive-root}`,均带环境变量覆盖(`src/main/resources/application.yml`)。新增路径类配置必须同步 backend 规范的 tmpdir 测试规则(F-02,见 `.trellis/spec/backend/quality-guidelines.md`——`app.sandbox.archive-root` 就是那次事故的主角)。

## 测试写法

- **纯 JUnit + AssertJ,不起 Spring 上下文**;唯一例外 `SandboxRunnerApplicationTest`(上下文冒烟,带 rabbitmq auto-startup=false 与 tmpdir 覆盖)。时间用注入 `Clock`,Docker 用 `DockerClient` 接口假实现,不依赖真实 daemon。
- 契约测试三件套(与 backend 对偶,字面量同批):`WorkspaceArchiveReferenceTest`(金标线格式 + 完整拒绝集)、`SandboxJobFieldOrderTest`(record 字段序快照)、`SandboxJobSignerTest`(固定向量 HMAC)。
- 运行:`cd sandbox-runner && mvn -B test`(本机无 mvn 时用 backend 规范里的 maven 容器命令,把 `-w` 指到 `/ws/sandbox-runner`)。
