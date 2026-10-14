# 修复需求软删后项目评审活动聚合的空指针

## Goal

需求软删（`deleted_at` 非空）后，其关联 PR 的 `requirement_id` 按设计保留（行留存是为了 `ai_call_log` 与 `pull_request_requirement_event` 的既成事实）。`ReviewActivityService.forProject` 先按未删除需求建分组 map，再遍历项目内全部 PR 并对 `perRequirement.get(requirementId)` 直接 `add`，遇到软删需求的 PR 时取到 null 抛 NPE，`GET /api/projects/{projectId}/review-activity` 返回 500，研发需求页与工作台整体不可用。

## Requirements

- 项目级聚合跳过「需求已软删」的 PR：这些 PR 的活动不计入任何需求，也不再引入已删除需求的分组。
- 需求级接口 `forRequirement` 对软删需求继续返回 404（现有行为不变）。
- 不改 SQL：`ReviewActivityRepository` 的 `left join requirement` 刻意不过滤 `deleted_at`，因为它只用于取 `current_revision_id` 判定当前有效 Review；过滤会改写判定。
- 不新增表、列、端点；不改 PRD 的活动映射表。

## Acceptance Criteria

- [x] `ReviewActivityTest` 新增用例（已编写，未执行）：一条需求挂两个 PR（一个有当前有效 APPROVED Review，一个无 Review），将该需求置为 CANCELED 并软删后，`forProject` 不抛异常，返回的 map 只含未删除需求；HTTP `GET .../review-activity` 返回 200 且不含该需求键；`forRequirement` 对该需求返回 404。
- [ ] 现有 `ReviewActivityTest` 全部用例保持通过。（待 CI：本机无 JDK 21 与 Docker）
- [x] `docs/v2/API.md` 资源删除一节补充：软删需求不再出现在项目级 `review-activity` 聚合中，其关联 PR 的活动不计入任何需求。

## Notes

- 轻量任务，PRD-only。
- 本机无 JDK 21 与 Docker，`./mvnw -B -ntp verify` 需由 CI 或容器路径执行；提交前以此为验证证据。
