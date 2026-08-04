# r1 CI 观察窗口记录与 trivy 扫描证据

> 对应验收项:「trivy 扫描日志证实真实执行(截取存档)」「若报出漏洞:CRITICAL 已处理,其余已登记去向」。
> 执行日期:2026-08-04。执行分支:integration/track-ab。

## 一、F-01 修法偏差记录(PRD 预设修法被上游变化推翻)

- PRD 预设:`@0.28.0` → `@v0.28.0`(commit `cd09a9d`)。
- 实测(run 30886328003):`v0.28.0` tag 存在,但其内部钉住的复合 action `aquasecurity/setup-trivy@v0.2.1` **已被上游删除 tag**(setup-trivy 现存 tag 仅 v0.2.6/v0.3.0/v0.3.1),同病者含 v0.29.0(钉 v0.2.2)。报错原文:
  `##[error]Unable to resolve action aquasecurity/setup-trivy@v0.2.1, unable to find version v0.2.1`
- trivy-action 自 v0.33.1 起改为按 commit SHA 钉 setup-trivy,不受删 tag 影响。
- 最终修法:升至当前最新稳定版 `@v0.36.0`(commit `bb11cd7`)。所用输入参数(scan-type/scan-ref/scanners/severity/ignore-unfixed/exit-code)均为稳定核心参数,行为不变。

## 二、门禁首次真实运行的产出(run 30886988620,commit bb11cd7)

- `Scan dependency manifests (Maven + npm + Python)`:**success**——文件系统扫描真实执行,无可修复的 HIGH/CRITICAL。
- `Build images`:**success**——四个业务镜像首次在 CI 构建成功。
- `Scan images`(deploy/scan-images.sh):**failure(门禁按设计咬合)**,逐镜像结果:

| 镜像 | 基底 | 结果 |
| --- | --- | --- |
| reposage-backend:ci | — | ❌ 1 × HIGH(见下) |
| reposage-frontend:ci | alpine 3.24.1 | Total: 0 (HIGH: 0, CRITICAL: 0) |
| reposage-sandbox-runner:ci | alpine 3.23.5 | Total: 0 (HIGH: 0, CRITICAL: 0) |
| reposage-model-service:ci | debian 13.6 | Total: 0 (HIGH: 0, CRITICAL: 0) |

backend 命中明细(日志截取):

```
│ org.springframework.data:spring-data-commons (app.jar) │ CVE-2026-41695 │ HIGH │ fixed │ 3.5.11 │ 4.0.6, 3.5.12 │ Spring Data Commons: Denial of Service via crafted property... │
FAIL: 存在已有修复版本的 HIGH/CRITICAL 漏洞,升级依赖或基础镜像后重扫
```

## 三、漏洞处置记录

- **CVE-2026-41695(HIGH,DoS)**:虽非 CRITICAL,但门禁设计为「有修复版本的 HIGH/CRITICAL 即拦」,不修则 CI 无法转绿、r1 验收与 r2 依赖均被阻塞,故**当场修复**而非登记转出(此为对 PRD「其余记录后转入」条款的有意偏差,原因如上):
  - 修法:backend/pom.xml 按既有惯例加属性覆盖 `<spring-data-bom.version>2025.0.12</spring-data-bom.version>`(commons 3.5.11 → 3.5.12,patch 级),沿用 CVE 注释风格。
  - 兜底:容器内 backend 全量测试 + CI 全流程。
- 其余需登记转出的漏洞:**无**(其他三镜像与 manifests 扫描均 0 HIGH/CRITICAL)。

## 四、verify 作业首次全程执行(run 30886328003,commit 31373e6)

F-02 修复生效,以下步骤 12 天来首次在 CI 实际执行且全部通过:
`Verify backend` / `Verify sandbox runner` / `Install+Audit frontend` / `Test frontend` / `Build frontend` / `Test model service`——F-06(lockfile 换源)预告的风险未发作,npm ci 在官方源下正常安装。

## 五、最终绿色 run

run **30888394125**(commit `0a369c3`,2026-08-04)——**三作业全绿,12 天连红终结**:

- `verify`:success(backend / sandbox-runner / 前端安装+audit+测试+构建 / model-service 全过)。
- `nginx-headers`:success。
- `supply-chain`:success,全步骤真实执行,镜像扫描日志截取:

```
==> 扫描 reposage-backend:ci        Total: 0 (HIGH: 0, CRITICAL: 0)   ← commons 3.5.12 修复生效
==> 扫描 reposage-frontend:ci       Total: 0 (HIGH: 0, CRITICAL: 0)
==> 扫描 reposage-sandbox-runner:ci Total: 0 (HIGH: 0, CRITICAL: 0)
==> 扫描 reposage-model-service:ci  Total: 0 (HIGH: 0, CRITICAL: 0)
```

r1 涉及提交:`cd09a9d`(F-01 v 前缀) → `31373e6`(F-02 隔离归档路径) → `bb11cd7`(F-01 续,升 v0.36.0) → `0a369c3`(CVE-2026-41695 修复)。
