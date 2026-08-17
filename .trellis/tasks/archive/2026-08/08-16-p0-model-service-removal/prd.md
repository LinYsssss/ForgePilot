# P0 架构清理:model-service 下线

> 父任务:`.trellis/tasks/08-16-forgepilot-upgrade`(P0,implement.md §2)。
> 决策依据:model-service 耦合面仅 `ReviewProcessor` 一处调用(父 prd §2);砍掉后 CI 更快、
> 部署少一个进程、论文叙事聚焦 LLM 上下文增强主线。
> **不做改名**(D2/D7);`model-service/` 目录本体保留不删,仅下线运行链路。

## Goal

把 model-service 从运行链路、部署编排、CI、验证脚本与活跃文档中移除;
`model-service/` 源码目录保留(不再被引用),spec 标注 archived。

## Requirements(触点清单,2026-08-16 逐项核查)

**后端(编译链):**

- R1 删 `backend/src/main/java/com/example/codereview/model/` 包 4 类
  (`ModelRiskClient` / `ModelRiskSignal` / `HttpModelRiskClient` / `NoopModelRiskClient`)。
- R2 `review/ReviewProcessor.java`:删字段/构造参数/`predict` 调用(L64)与
  `buildReviewContext` 的 riskSignal 合并分支(L164-173),上下文退化为纯 RAG。
- R3 `review/ReviewProcessorTest.java`:删 `@Mock ModelRiskClient` 及相关桩。
- R4 `ai/AiCallLogService.java`:删 `MODEL_RISK` 常量与 `modelRiskSuccess`/`modelRiskFailed`
  (唯一调用方是 HttpModelRiskClient;前端无 MODEL_RISK 引用,已核查)。
  历史 ai_call_log 数据行不动(只是不再产生新行)。
- R4b `common/api/ErrorCode.java`:删 `MODEL_SERVICE_UNAVAILABLE`(冻结契约显式变更:
  该常量全仓零抛出点、从未上过线上响应,移除对任何消费方不可观察;分节注释同步改)。
- R4c `config/ModelServiceHealthIndicator.java`:整类删除(actuator health 少一个
  `modelService` 组件,disabled 时它恒报 UP,移除不改变整体健康结论)。
- R5 `resources/config/app-boundary.yml`:删 `app.model-service` 配置块(L52-54)。

**部署与 CI:**

- R6 `deploy/docker-compose.yml`:删 `model-service` 服务定义(L125-138)与
  backend `depends_on.model-service`(L69-70)。
- R7 `deploy/.env.example`:删 `MODEL_SERVICE_ENABLED` / `MODEL_SERVICE_URL`(L19-20)。
- R8 `deploy/scan-images.sh`:默认镜像列表去掉 `deploy-model-service:latest`(L15)。
- R9 `.github/workflows/ci.yml`:删 "Test model service" step(L72-76)、
  model-service 镜像构建(L112)与扫描参数(L115)。

**脚本:**

- R10 `scripts/verify-local.ps1`:删 `$ModelDir`、`Invoke-ModelCheck` 及 "Model service check" step。
- R11 `scripts/verify-local.sh`:删 `run_model_service` 函数及其调用。

**文档与 spec(仅活跃文档,`docs/archive/` 不动):**

- R12 `README.md`(L137/L186)、`docs/01_系统架构设计说明书.md`(架构图/配置)、
  `docs/08_部署环境与配置清单.md`、`docs/11_本地开发与联调手册.md`、
  `docs/12_服务器部署与演示手册.md`、`docs/PR守门Agent SCM与Sandbox运维验收.md`(L78)、
  `docs/完整功能测试方案.md`(16-4 用例与 M21 行标记废弃)、
  `evaluation/tools/eval-stack.override.yml`(L11 注释):去掉 model-service 引用或标注已下线。
- R13 `.trellis/spec/model-service/index.md` 顶部标注 archived(服务已下线,目录保留)。
- `.gitignore` 的 model-service 条目保留(目录仍在,防止误提交缓存)。

## Acceptance Criteria

- [ ] A1 `backend` 编译与测试通过(`mvn -s .mvn/settings.xml verify` 一次,阶段末)。
- [ ] A2 全仓 grep:`MODEL_SERVICE|ModelRisk|MODEL_RISK` 在活跃代码/配置/CI/脚本零残留
  (`model-service/` 目录自身、`docs/archive/`、`.trellis/` 历史任务除外)。
- [ ] A3 ci.yml 无 model-service 相关 step;compose config 可解析。
- [ ] A4 不触碰 `agent/model/**`(结构化输出包,同名不同物)与 Flyway 既有迁移。

## Notes

- 用户指令(2026-08-16):**非必要不测试**,完成后用户自行部署实际环境验证;
  故过程不跑测试,仅阶段末一次 `mvn verify`(删代码必须确认编译与存量用例)+ 前端不涉及不跑。
- 回滚点:单独合批提交(中文提交信息走 Write + `git commit -F`)。
