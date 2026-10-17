# 全面检查收尾:测试顺序依赖、前端依赖与文档小账

## 目的

2026-10-17 的全量排查未发现代码缺陷,留下三件可闭环的事项:测试套件的类执行顺序依赖(根因未定位)、
前端 5 个高危依赖漏洞、几处文档小账。本轮按**最小实现改动**处理这三件。

## 范围与做法

### 一、测试套件的类执行顺序依赖(定位根因,最小修复)

现状(TEST-REPORT 9.1):仅重命名 `BatchOneApiTest` 类名,该类 4 个用例在完整构建中失败,
断言为 `GET /api/auth/me must issue the XSRF-TOKEN cookie`(cookie 为 null);单类运行 5/5 通过。
2026-08-29 两次复现尝试(反字母序、新增三个测试类)均未复现,根因未定位。
当前处置只是保留历史类名,未加 `@DirtiesContext` 之类的兜底——那是项目明确拒绝的创可贴。

已掌握的线索:
- surefire 默认按文件系统顺序执行;重命名会把文件挪到目录项末尾,从而改变该类的执行位置。
- 限流只作用于 `POST /api/auth/login` 与 `POST /api/auth/register`,`GET /api/auth/me` 不受限,
  因此「429 导致没有 cookie」解释不通;且各测试类已各自分配源地址,地址段互不重叠。
- CSRF 是 `CsrfConfigurer::spa`,每个响应都应带 `XSRF-TOKEN`;缺失 cookie 更可能是
  响应被重置(例如异常走了 ERROR dispatch)而不是根本没经过 CsrfFilter。

做法:
1. **在仓库外**的临时副本(`$CLAUDE_JOB_DIR/tmp/order-repro`)里重命名该类,用 DEFENSE-GUIDE 的
   固定容器命令跑全量 `verify`,复现失败;复现时在副本里临时增强断言输出(打印响应状态、
   错误体、Set-Cookie 头),让失败自己说明是哪一类响应。
2. 依据证据定位污染源;必要时在副本里用 `-Dtest=A,B` 成对运行二分。
3. 在**仓库里**做最小修复:若属测试侧状态泄漏,只改测试侧;不改生产语义。
4. 验收:仓库全套 verify 绿;且在副本里用改名后的类再跑一次全量 verify,证明顺序无关。

预算与止损:全量复现最多 2 次尝试(每次约 5 分钟);若仍无法复现/定位,如实记录本轮排除的假设
与证据,不加任何「以防万一」的处置。这一点本身就是结论。

### 二、前端 5 个高危依赖漏洞

`npm audit` 报 5 个 high,全部有修复版本:

| 包 | 现状 | 修法 |
|---|---|---|
| `vue` | 3.5.41(精确锁定) | 升到 3.5.44(patch,非大版本) |
| `@vue/compiler-sfc` | 3.5.41 | 与 vue 同步升到 3.5.44(两者必须同版本) |
| `@vue/server-renderer` | 3.5.41(随 vue) | 随 vue 升级而消失 |
| `brace-expansion` | 2.1.4(经 minimatch←editorconfig←js-beautify←@vue/test-utils) | `npm audit fix` 只动 lockfile |
| `source-map-js` | 1.2.1(经 postcss/@vue/compiler-core) | `npm audit fix` 只动 lockfile |
| `undici` | 8.10.0(经 jsdom) | `npm audit fix` 只动 lockfile |

做法:改 `frontend/package.json` 两处精确版本 → `npm install` 刷新 lockfile →
`npm audit fix` 处理传递依赖;验收 `npm audit` 为 0,再跑前端四项门
(`lint` / `typecheck` / `test -- --run` / `build`)与一次 `npm ci`(lockfile 自洽)。

不升:其余 `outdated` 里的大版本跳跃(typescript 7、vitest 5、vite 8.3 等)与漏洞无关,不在本轮。

### 三、文档小账(每处一行)

1. `docs/deliverables/TEST-REPORT.html`:2026-09-30 补充段仍写「尚未提交或部署」,而该内容
   于 2026-10-02 已上线;改为与相邻 10-01 段一致的措辞。
2. 符号链接说明:`.trellis/tasks/08-22-phase-8-gitlab-evaluation-defense/evidence` 是指向
   `archive/2026-08/...` 的**被跟踪的符号链接**,冻结校验按该路径读取(`configuration-freeze.json`
   的 `freezeArtifact` 字段);仓库内无人知道它是承重的。在 `AGENTS.md` 不可变资产段加一句。
3. 会话记录:`.trellis/workspace/LinYsssss/index.md` 停在 10-14(#27);补记 10-16 的部署与
   文档收尾两轮,以及本轮。

## 边界(不做)

- 真实浏览器人工验收(89 项清单)与 review-5 线上复测:用户侧/论文侧,不在本轮。
- 后端 Maven 依赖的 CVE 扫描与门禁:新增门禁不属于最小改动。
- 生产代码语义改动:除非第一项的证据指向生产缺陷,届时单独评估再动。

## 验收标准

1. 测试顺序依赖:要么有最小修复并附「改名后全量 verify 亦绿」的证据,要么有本轮排除假设的完整记录。
2. `npm audit` 0 漏洞;前端四项门与 `npm ci` 全绿。
3. `docs/` 中不再出现「尚未提交或部署」;`AGENTS.md` 含符号链接说明;会话表补记完整。

## 提交计划(推送前按仓库规则另行确认)

- `test(backend)`:顺序依赖的最小修复 + 本轮任务记录(prd/result)。
- `chore(frontend)`:依赖升级(package.json + package-lock)。
- `docs`:TEST-REPORT 措辞、AGENTS.md 符号链接说明、workspace 会话记录。

## 结果(2026-10-17)

1. **顺序依赖:根因已定位,已修复(测试侧)。** 污染源是 `spring-security-test` 的
   `.with(csrf())`:它经 `WebTestUtils.setCsrfTokenRepository` 把过滤器链里 `CsrfFilter` 的
   `CsrfTokenRepository` 换成 `TestCsrfTokenRepository`(包着 `HttpSessionCsrfTokenRepository`),
   token 从此写进 session 而非响应 cookie;替换留在共享链上,污染其后所有测试类。
   6 个类 14 处改为 `PostgresTestBase.spaCsrf()`(cookie + header 回显同一值)。
   复现器修复前 139 项 6 失败,修复后 139/139 绿。生产代码未改。
   证据与验收见 `validation.md`。
2. **前端依赖:** `vue` / `@vue/compiler-sfc` 升到 3.5.44,lockfile 刷新,`npm audit` 0 漏洞。
3. **文档小账:** TEST-REPORT §9.1 改为「已修复」并补根因与规则、10.4 去掉已完成项;
   AGENTS.md 承重符号链接说明;工作区会话记录补记。
