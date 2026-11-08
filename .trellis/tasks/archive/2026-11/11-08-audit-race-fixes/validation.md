# 验证记录（2026-11-08）

所有数字均来自本次实际运行。

| 门禁 | 命令 | 结果 |
|---|---|---|
| 定向后端 | 容器 `mvnw test -Dtest='RequirementQualityTest,ScmIdentityFreezeConcurrencyTest,GitHubWebhookIngestionTest,GitLabWebhookIngestionTest,ScmRepositoryApiTest'` | 14 / 3 / 13 / 7 / 10，全部 0 失败 |
| 承重性（后端） | 撤回两份生产代码后跑 `RequirementQualityTest,ScmIdentityFreezeConcurrencyTest` | 17 项中 3 项失败，正是新增的 3 条；随后恢复修复 |
| 承重性（前端） | 撤回 `RequirementDetailPage.vue` 后跑 `tests/requirement.spec.ts` | 9 项中 1 项失败，正是新增的那条；随后恢复修复 |
| 后端完整 | DEFENSE-GUIDE §2 容器 `mvnw -B -ntp verify` | **446 项 / 0 失败 / 0 错误 / 0 跳过**，BUILD SUCCESS，4:03 |
| 前端 | `npm run lint`、`npm run typecheck`、`npm test -- --run`、`npm run build` | 全过；**18 文件 / 71 项** |

未改动：迁移（仍为 15 条）、表数、`ReviewPrompts.VERSION`、`FindingKeys.RULE_VERSION`、`QUALITY_VERSION`，以及冻结评测资产。未重跑正式评测，未部署。
