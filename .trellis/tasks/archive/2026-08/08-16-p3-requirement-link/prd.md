# P3 需求-代码关联

> 父任务 `.trellis/tasks/08-16-forgepilot-upgrade`(R4/A4,design §4)。

## Goal

`requirement_link` + 三类自动提取器(分支/commit/PR)+ 手动关联兜底 + 需求详情关联视图;
四问查询 = 正查(需求→links)+ 反查(ref→需求)两个端点。

## Requirements

- R1 V32 `requirement_link(id, project_id, requirement_id fk cascade, type, ref, source, created_at)`,
  `unique(requirement_id, type, ref)`;type ∈ {BRANCH, COMMIT, PULL_REQUEST},
  source ∈ {AUTO, MANUAL};project_id 冗余存储供反查(join 省一跳)。
- R2 提取规则:有界正则 `\bREQ-(\d+)\b`;seq → (projectId, seq) 定位需求;幂等 upsert
  (唯一约束兜底,重复提取零副作用);目标需求不存在则静默跳过(不报错)。
- R3 提取器挂点(全部 best-effort,提取失败不破坏宿主链路):
  - 分支:`RepositoryService.commits`(交互式"加载 Commit"= 事实同步点)扫远端分支列表
    (新增 `GitCliService.listBranches`,上限 200),命中 → BRANCH ref=分支名。
  - Commit:同点扫本次列出的 commit message(数量有界=listCommits limit),
    命中 → COMMIT ref=sha。
  - PR:交互式 `PullRequestService.create/update` 与 webhook `WebhookAgentRunService.startFromEvent`
    解析 title + sourceBranch,命中 → PULL_REQUEST ref="PR#<number>" + BRANCH ref=源分支。
- R4 手动兜底端点:POST `/requirements/{rid}/links` {type, ref}(LEADER/DEVELOPER,source=MANUAL);
  DELETE `/requirements/{rid}/links/{linkId}`(LEADER/DEVELOPER);GET `/requirements/{rid}/links`(成员)。
- R5 反查端点:GET `/requirements/links/lookup?type=&ref=`(成员)→ [{requirementId, code, title,
  status}];PR 详情页的反查 UI 随 P4 PR 页迁移落地(本任务只交付 API)。
- R6 前端:需求详情内嵌「代码关联」块——类型徽章 + ref + 来源(自动/手动)、手动添加表单、移除。
- R7 清理:项目删除级联删 links(应用层,按 project_id)。

## Acceptance Criteria

- [ ] A1 提取幂等:同一 PR 事件/同一分支重复扫描不产生重复行;REQ 号不存在不报错。
- [ ] A2 集成:绑定仓库后建 title 含 REQ-1 的 PR → REQ-1 的 links 出现 PULL_REQUEST 与 BRANCH;
  手动添加/删除/反查全链路可用;REVIEWER 手动添加 403。
- [ ] A3 阶段末 mvn verify + npm test/build 全绿。

## Notes

- webhook 提取同步执行(正则+两次查询,微秒级),不入队;失败 catch 后只记日志。
