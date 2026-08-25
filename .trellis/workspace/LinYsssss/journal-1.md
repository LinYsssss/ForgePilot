# Journal - LinYsssss (Part 1)

> AI development session journal
> Started: 2026-08-19

---



## Session 1: Initialize Trellis and prepare final execution plan

**Date**: 2026-08-19
**Task**: Initialize Trellis and prepare final execution plan
**Branch**: `main`

### Summary

Configured Claude Code, Codex, and Pi around one Trellis workspace and consolidated the ForgePilot V2 candidate for user review without writing application code.

### Main Changes

- Initialized official Trellis platform assets for Claude Code, Codex, and Pi under developer LinYsssss.
- Added a shared review gate and final execution plan; aligned V2 documents to waiting-for-approval status.

### Git Commits

(No commits - planning session)

### Testing

- [OK] trellis platforms reports Claude Code, Codex, and Pi.
- [OK] Trellis context resolves LinYsssss and the review task remains planning.
- [OK] Task validation and git diff --check pass; no application scaffold was added.

### Status

[OK] **Completed**

### Next Steps

- User reviews the 12 decisions in docs/v2/FINAL-EXECUTION-PLAN.md.
- Do not create or start the Phase 1 task until explicit approval.


## Session 2: Phase 1 minimal greenfield foundation: review, fixes, evidence and acceptance

**Date**: 2026-08-21
**Task**: Phase 1 minimal greenfield foundation: review, fixes, evidence and acceptance
**Branch**: `main`

### Summary

Reviewed the Phase 1 slices with three read-only agents and verified every claim against source; fixed 5 backend and 13 frontend findings; repaired three capacity-collector defects (vacuous kernel-OOM gate, missing Metaspace sampling, StartedAt not compared); re-ran the full 5+2+4 capacity protocol to a 19/19 PASS on the current compose; recorded result.md. Committed in 8 groups, not pushed.

### Git Commits

| Hash | Message |
|------|---------|
| `3482473` | (see git log) |

### Status

[OK] **Completed**


## Session 3: 批次 1：Auth / Project / Requirement 三个切片落地

**Date**: 2026-08-21
**Task**: 批次 1：Auth / Project / Requirement 三个切片落地
**Branch**: `main`

### Summary

Phase 2+3 完成：六张表、三个后端切片、七条 ArchUnit 规则、五个前端界面；59 个后端测试与前端五条命令全绿，Compose 冷启动与 CI 四个 job 全绿。

### Main Changes

- V2/V3 两条迁移建六张表，项目内引用一律复合外键；requirement 自引用键保持 MATCH SIMPLE + NOT DEFERRABLE，创建走三步回填
- 实体统一 D013.1 变体 A（关联只读、标量写入），ddl-auto=validate 在启动期即验证映射形态
- auth：表单登录 + 进程内 HttpSession + cookie CSRF + session_version 撤销；失败响应体对未知用户与错误口令完全一致
- project：ProjectAccessService 为唯一授权入口；LEADER 转移改为先锁 project 行再查角色，堵住失败方基于陈旧读继续操作的缺陷
- requirement：Revision 冻结、ac_key 跨版本稳定、状态机以数据编码，IN_DEVELOPMENT 只能由首次指派进入
- ArchUnit 增至七条（子包白名单、Spring Data 类型识别 Repository），两条新规则各配反证 fixture
- 前端五个界面填入既有路由外壳，未新增一级菜单、未新增依赖

### Git Commits

| Hash | Message |
|------|---------|
| `5954f1c` | (see git log) |
| `c303586` | (see git log) |
| `f1d02e1` | (see git log) |
| `248d3ee` | (see git log) |
| `351ebf4` | (see git log) |
| `22cb740` | (see git log) |
| `e2bc73b` | (see git log) |
| `be836f7` | (see git log) |
| `f6c93b2` | (see git log) |

### Testing

- [OK] backend: mvnw verify — Tests run 59, Failures 0, Errors 0, Skipped 0
- [OK] frontend: npm ci / lint / typecheck / test --run / build 五条全部退出码 0（15 个测试）
- [OK] BatchOneApiTest 跨切片 HTTP 闭环首次运行即全绿，证明三个独立编写的切片契约一致
- [OK] Compose 空库冷启动通过，public 下恰好预期六张表，/actuator/metrics 仍为 404
- [OK] curl 沿 nginx 代理跑通完整业务流程，补上 JVM 内测试证明不了的那一跳
- [OK] CI run 32471329945：四个 job 全部 success（Phase 1 遗留项闭环）

### Status

[OK] **Completed**

### Next Steps

- 批次 2（Phase 4+5）尚未授权，需人工评审批次 1 后单独授权
- 批次 2 前须先回答：成员移出项目时 requirement.assignee 如何处置
- 若引入禁用账户接口，必须同时递增 session_version，否则已存在会话不会失效


## Session 4: 批次 2（Phase 4+5）完成并过闸

**Date**: 2026-08-21
**Task**: 批次 2（Phase 4+5）完成并过闸
**Branch**: `main`

### Summary

AI Gateway、Knowledge、GitHub SCM 三条切片落地；13/16 表；155 测试全绿；D014 闸门五条自证通过

### Main Changes

- 七张新表：knowledge_document / requirement_attachment / knowledge_chunk / scm_repository / pull_request / pull_request_requirement_event / ai_call_log
- AI Gateway：超时 + 恰好一次 retry + ai_call_log 落库，无凭据测试打到 JDK 自带 HTTP 服务器
- Knowledge：分块、embed、项目内 TopK 检索；提升为公共知识是复制而非改写
- SCM：原始字节验签、Provider 权威快照、确定性 fingerprint、REQ-<n> 按项目解析
- 补 D016 正式化两处偏离：超限只拒绝不标记、P1 的 DEVELOPER 半条推迟到批次 3

### Git Commits

| Hash | Message |
|------|---------|
| `0d1ee92` | (see git log) |
| `8179d66` | (see git log) |
| `2892059` | (see git log) |
| `635d78f` | (see git log) |
| `2da43e3` | (see git log) |
| `e560f22` | (see git log) |
| `7daf632` | (see git log) |

### Testing

- [OK] mvnw verify：155 tests, 0 failures, 0 errors, 0 skipped
- [OK] Compose 空库冷启动：退出码 0，三服务健康，13 张表逐名比对
- [OK] CI 2892059 四 job 全绿；ci.yml 中无 secrets.*

### Status

[OK] **Completed**

### Next Steps

- 批次 3（Phase 6+7）：review / finding / finding_event 三张表，补 P1 DEVELOPER 半条与 ai_call_log.review_id 外键


## Session 5: 批次 3（Phase 6+7）完成并过闸

**Date**: 2026-08-22
**Task**: 批次 3（Phase 6+7）完成并过闸
**Branch**: `main`

### Summary

Review Engine、人工闭环、容量冻结、development 三臂评测和独立 Provider 落地；D014 四 job CI 全绿并归档

### Main Changes

- 落地唯一 Review Engine、fencing、Finding continuity 与 Decision 闭环
- 最大预算实测冻结并发为 2，并完成 gpt-5.6-luna development 三臂评测
- Chat/Embedding 独立 Provider 与 Requirement Quality 真实业务烟测通过

### Git Commits

| Hash | Message |
|------|---------|
| `04b1af2` | (see git log) |
| `75ac88e` | (see git log) |
| `47bfe6f` | (see git log) |
| `fa82bda` | (see git log) |
| `e657bf2` | (see git log) |
| `072d986` | (see git log) |

### Testing

- [OK] JDK 21 verify：298 tests，0 failure/error/skip
- [OK] CI run 32574477108：Evaluation、Frontend、Backend、Compose 四 job 全绿

### Status

[OK] **Completed**

### Next Steps

- 单独规划 Phase 8；holdout 只在配置冻结后运行一次


## Session 6: Complete frontend capability surface

**Date**: 2026-08-22
**Task**: Complete frontend capability surface
**Branch**: `main`

### Summary

Completed account and Requirement workflows, project Review discovery, structured evidence, Finding audit comments, responsive Chromium verification, specs, and delivery evidence.

### Git Commits

| Hash | Message |
|------|---------|
| `952bd48` | (see git log) |
| `4c72b1c` | (see git log) |
| `d0af0e7` | (see git log) |

### Status

[OK] **Completed**


## Session 7: Complete D017 product flow

**Date**: 2026-08-23
**Task**: Complete D017 product flow
**Branch**: `chore/chinese-comments-and-docs`

### Summary

Delivered and verified the six-entry frontend, real-data workbench, Knowledge and attachment flows, structured knowledge-enhanced Guidance, SCM safe read, branding, AI prominence, and vector metadata; archived the task after 310 backend and 34 frontend tests passed.

### Main Changes

- Added six-entry responsive product shell, workbench, Knowledge and Repository pages, and both official Logo assets.
- Completed requirement-scoped attachment persistence and vector retrieval isolation, structured knowledge-enhanced Guidance, and safe SCM configuration reads.

### Git Commits

| Hash | Message |
|------|---------|
| `d71533e` | (see git log) |
| `66436dd` | (see git log) |
| `2516be1` | (see git log) |
| `25c1961` | (see git log) |

### Testing

- [OK] Backend verify: 310 tests, 0 failures/errors/skips.
- [OK] Frontend lint, strict typecheck, 34 tests, and production build passed.

### Status

[OK] **Completed**

### Next Steps

- Push the current branch and deploy the existing Compose project without removing its PostgreSQL volume.


## Session 8: R2.5 product baseline closure

**Date**: 2026-08-23
**Task**: R2.5 product baseline closure
**Branch**: `chore/chinese-comments-and-docs`

### Summary

Aligned R2.5 documentation and UI baseline, fixed PR author association authorization, recorded exact sequential vector search, and archived the completed D018/R2.5 tasks.

### Git Commits

| Hash | Message |
|------|---------|
| `7872912` | (see git log) |
| `1bf5c53` | (see git log) |
| `56d03af` | (see git log) |
| `0fcfd9c` | (see git log) |
| `7db45d4` | (see git log) |

### Status

[OK] **Completed**


## Session 9: Requirement document access

**Date**: 2026-08-23
**Task**: Requirement document access
**Branch**: `chore/chinese-comments-and-docs`

### Summary

Delivered minimal full-stack .txt/.md requirement document upload, member reading/download, structured Markdown export, and existing Guidance reuse; backend 316/316 and frontend 35/35 green.

### Git Commits

| Hash | Message |
|------|---------|
| `850f83d5ebdf762bb0247191ada03ae8d5e2e814` | (see git log) |

### Status

[OK] **Completed**


## Session 10: Repository cleanup and completion audit

**Date**: 2026-08-23
**Task**: Repository cleanup and completion audit
**Branch**: `chore/chinese-comments-and-docs`

### Summary

Completed repository-wide cleanup, repaired archived documentation references, refreshed metadata, preserved formal evaluation evidence compatibility, and verified backend, frontend, evaluation, freeze integrity, and completion status.

### Git Commits

| Hash | Message |
|------|---------|
| `d198c59` | (see git log) |
| `7a3e967` | (see git log) |
| `b53cf26` | (see git log) |

### Status

[OK] **Completed**


## Session 11: Member directory and SCM identity management

**Date**: 2026-08-24
**Task**: Member directory and SCM identity management
**Branch**: `feat/member-directory-scm-identities`

### Summary

Implemented and verified account display names, multi-role project membership, user-owned verified SCM identities, project bindings, V8 migration, frontend flows, clean deployment reset, and documentation. Backend 317 tests and frontend 35 tests passed; isolated Compose smoke and fp-demo redeployment are healthy with 19 empty business tables.

### Git Commits

| Hash | Message |
|------|---------|
| `9a0e52e` | (see git log) |

### Status

[OK] **Completed**


## Session 12: 前端体验修复 T-001..T-004/T-008/T-009

**Date**: 2026-08-25
**Task**: 前端体验修复 T-001..T-004/T-008/T-009
**Branch**: `feat/member-directory-scm-identities`

### Summary

六项纯前端缺陷一次交付：工作台默认项目、成员紧凑表与批量守卫、长 AI 输出定高、改密迁入 /account 与账户菜单三条关闭路径、两处 Provider Token 入口。零后端改动零迁移，前端 lint/typecheck/test/build 全绿，测试 35→37，已重建 frontend 容器并核验部署 bundle。AC7/AC8 的三档浏览器确认待人工执行。

### Git Commits

| Hash | Message |
|------|---------|
| `44a4bc9` | (see git log) |

### Status

[OK] **Completed**


## Session 13: 三类资源的删除语义 T-005/T-006/T-007（D022/V10）

**Date**: 2026-08-25
**Task**: 三类资源的删除语义 T-005/T-006/T-007（D022/V10）
**Branch**: `feat/member-directory-scm-identities`

### Summary

知识文档硬删+显式删chunk、成员硬删+进程内事件反转依赖撤销三处活权限、作废需求软删保住审计；新增留痕表 project_deletion_record（19→20 表，V10）。正面处理三处冲突：audit_event 禁令边界、project 依赖方向、ReviewActivityRepository 两条 SQL 不可一视同仁过滤。后端 331 测试、前端 37 测试全绿零跳过；已备份并部署，Flyway 9→10 成功，既有数据完好。AC18 浏览器闭环待人工。

### Git Commits

| Hash | Message |
|------|---------|
| `df624c6` | (see git log) |

### Status

[OK] **Completed**
