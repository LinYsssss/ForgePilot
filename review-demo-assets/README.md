# ForgePilot 审查演示资产

本目录归档了用于 ForgePilot 代码审查演示的三套项目代码与材料，便于在单一仓库中备份、交接和复用。

## 目录

- `requirements/`：独立需求文档、项目知识、操作说明与测试账号说明。
- `repositories/<仓库>/main/`：各项目 `main` 分支的代码快照。
- `repositories/<仓库>/review-branches/`：每仓 5 个审查分支的代码快照；每个分支包含其 `review-materials/PR-xx.md`。

## 包含的项目

| 项目 | 审查分支数 |
| --- | ---: |
| `forgepilot-demo-mall-order-service` | 5 |
| `forgepilot-demo-tenant-user-center` | 5 |
| `forgepilot-demo-payment-settlement-service` | 5 |

归档不包含各项目的 `.git` 目录、构建缓存或 Python 字节码；远程原始仓库与分支仍是代码审查的权威来源。
