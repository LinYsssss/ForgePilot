# 代码缺口改进:部署与数据生成记录(2026-10-18)

方案见同目录 `prd.md`。本文件记录执行结果:三处代码缺口已实现、测试、部署,
并用真实产品流程在 halo-replay(项目 4)产生工作台数据。

## 提交(本地,未推送)

| commit | 内容 |
| --- | --- |
| `ebb3712` | feat(knowledge): 处理失败的知识文档可重试(POST .../retry + requeue + 测试) |
| `0f74726` | feat(review): 列表带提示词与模型,新增 AI 调用台账只读视图(/ai-usage + 测试) |
| `4d6d9d3` | feat(web): 审查列表现引擎元数据,工作台 AI 台账面板,知识失败重试入口 |
| `a824143` | docs(trellis): 10-18 代码缺口方案与数据生成计划 |

未推送:按 AGENTS.md「先给出分组、确认后再推」。CI 未运行。

## 测试与部署

- 后端定向:`KnowledgeServiceTest` + `ReviewInsightTest` 21/21;全量 `mvnw verify` 434/434(BUILD SUCCESS,较上轮 +2)。
- 前端四门:typecheck / lint / 66 项 vitest / build 全绿。
- 部署:`docker compose -p fp-demo up --build --detach --wait`,三容器 Healthy;
  线上核对 `GET /api/projects/4/ai-usage` 返回真实聚合、审查列表行携带 `promptVersion`/`model`。
- 线上标识:backend `270fbe446d80` / frontend `c102f1d44833`(postgres 容器与卷未动,仍 `8dd86d1a690a`);
  公网 `https://yasinlin.com/` 200,容器内与公网资产一致(`index-BcklPhM_.js` / `index-Bp3YdVOU.css`);
  Flyway 仍 14,无迁移。

## 数据生成(全部真实产品路径,操作者 = rev01 评审一号)

1. **失败审查重试**:review 102(PR 39,两次失败的第三轮)经 `POST .../pull-requests/39/reviews`
   走真实重试路径,完成并产出 review-5 首个线上输出(1 条 finding,已驳回)。
2. **Finding 裁决:55 条逐条核实后裁决**(CONFIRM 42 / REJECT 13;每条都有 finding_event 审计)。
   核实方法:对实现类断言逐条打开被测 PR 的 reviewed head 源码原文核对;
   对测试类断言打开 cited 测试文件原文核对。典型误报与驳回理由:
   - `304`:实现第 170 行已有 `.distinct()`(声称缺失去重);
   - `372`:PluginServiceImpl 与 PluginReconciler 用的是同一个 `VersionUtils.satisfiesRequires`(声称两处规则可能不一致);
   - `369/299/178`:`shouldStartCorrectly` 已覆盖「对象不存在时仍创建」(声称未覆盖);
   - `295`:单通知器用例存在且带具体断言;`379`:回退断言完整;`185`:根选择先算全量 descendants,与遍历顺序无关。
3. **审查决定**:3 条 REQUEST_CHANGES(审查 168 / 170 / 179,均附理由,对应已确认的 finding;
   无远端副作用)。其余 20 条「已完成未决定」保持队列原样。未做 APPROVE——桥接会按被审 SHA 真实合并 GitHub PR。

## 结果(线上实测)

- 校准表(工作台):高 47 裁决 / 39 确认 / 83%,95% 区间 70%–91%;中 8 / 3 / 38%,区间 14%–69%;
  低档无样本显示「样本不足」。待裁决 0、无置信度 0。
- 工作台 KPI:开发中需求 24 / 待最终决定 21 / 进行中的审查 0 / 待发起审查 0;AI 台账面板 388 次调用 · 4 种用途
  (代码审查 219 次含重试新增,知识向量化 163 次)。
- 截图(`screenshots-20261018/`):`01-workspace-full`(工作台全页)、`02-calibration`(校准表)、
  `03-ai-usage-panel`(台账展开)、`04-reviews-table`(提示词 · 模型列与三条已退回)、
  `05-review-168-decided`(退回决定与 finding 裁决明细)。

## 遗留

- 推送待确认;推送后 CI 复核,并补记 CI run id。
- 数据是真实的,但「最近动态」的时间戳来自 09-25 的审查行(PR head 未变,审查身份幂等,
  不能为制造新数据而改 head 或需求修订)。
