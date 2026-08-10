# 后端分批重构

> 前置：r4 规范就位（对着规范重构，而不是对着品味重构）。前端拆分已由 `07-31-frontend-split` 完成（App.vue 1540→58 行），本任务只管后端；与 r6 前端美化可并行（不同目录，无冲突面）。

## Goal

后端结构可维护：无死代码、无巨型类、模块边界与依赖方向清晰。全程行为不变——每批改动前后测试集必须同绿，不新增功能、不改契约。

## Requirements

### 批次划分（风险递增，每批独立提交独立可回滚）

**批A 零风险清理**
- 死代码：未引用的类/方法/配置项（以 IDE 分析 + `mvn dependency:analyze` 为证据，人工确认后删）。
- 陈旧资源：`.worktrees/pr-gatekeeper-agent` 残留工作树、无效脚本、过期注释；docs/archive 之外的陈旧文档段落归档。
- 格式统一：仅在被触碰的文件内顺带整理，不做全仓格式化大提交（避免污染 blame）。

**批B 低风险结构**
- 超长类/方法拆分：以 500 行类 / 60 行方法为筛查线（阈值按 r4 spec 定稿为准），拆分时保持公共签名不变。
- 命名对齐 r4 规范：仅限包内私有命名；公共 API 命名不动。

**批C 模块边界**
- 按领域收敛包结构（review / agent / scm / sandbox / knowledge / infra 的边界以 r4 spec 划定为准），消除跨域直接引用，必要处引入接口。
- 重复逻辑收敛为公共组件（以三处以上重复为准入线，两处重复不抽象——避免过度设计）。

### 硬约束

- 冻结契约不动：ErrorCode / PageResponse / ProjectAuthorization 签名、Flyway 已执行迁移、REST 路径与字段名、MQ 载荷格式。
- 每批期间 `mvn -s .mvn/settings.xml verify` 全绿才能进下一批；行为对比以现有测试集为准，测试不足以覆盖的重构点先补特征测试再动。
- 拆分/移动类时保持 git 可追溯（单独的 move 提交与 modify 提交分离）。

## Out of Scope

- 新功能；性能优化（除非顺带且零风险）；sandbox-runner 与 model-service 重构（规模小，暂无必要，发现问题另立任务）；前端一切。

## Acceptance Criteria

- [ ] 三批各自独立提交，每批提交后 CI 绿。
- [ ] 批A：死代码清单（删除依据）留档本任务目录；`.worktrees` 残留清理。
- [ ] 批B：筛查线以上的类/方法数量前后对比留档（写实数字，不美化）。
- [ ] 批C：包依赖方向可画出无环图；重复逻辑收敛点列表留档。
- [ ] 全程无契约变更：`git diff` 复核 REST 路径 / DTO 字段 / 迁移文件零改动。
- [ ] 部署服务器端到端演示流程复跑通过（登录→审查→报告→Agent 工作台）。

## Validation

```bash
cd backend && mvn -s .mvn/settings.xml verify
mvn dependency:analyze | grep -E "Unused|Undeclared"
bash ../scripts/verify-demo-repos.sh
```
