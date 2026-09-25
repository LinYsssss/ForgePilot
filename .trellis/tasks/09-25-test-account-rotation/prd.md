# 轮换已泄露的测试账号密码并移出仓库

## Goal

`review-demo-assets/requirements/测试账号.txt` 随 `c63d654`（2026-09-02）进入公开仓库，dev01～dev05、rev01～rev05 共用的密码因此公开。rev01 在各项目有终局决定权，APPROVE 会真实合并 GitHub PR。

## Requirements

- 经 `POST /api/auth/password` 逐个轮换 10 个测试账号：新密码随机生成，只写进本机 `/root/forgepilot-demo-docs/测试账号.txt`，不进命令行、日志或仓库。改密会递增 `session_version`，顺带让可能存在的外部会话失效。
- ysainlin 的密码不在泄露文件里，不动。
- 从仓库删除该文件；`review-demo-assets/README.md` 改为说明账号清单只保存在本机。
- 不改写公开历史：旧提交里仍能看到旧密码，轮换才是真正的修复。

## Acceptance Criteria

- [x] 10 个账号用旧密码登录均为 401，用新密码登录为 200。
- [x] `git grep` 在 HEAD 中找不到旧密码与新密码。
- [x] 本机文件权限 600。
