# Design：全仓审计与安全清理

## 1. Four-track audit

### Track A — Repository hygiene

使用 `git ls-files`、`git ls-files -ci --exclude-standard`、`git clean -nd/-ndX`、hash duplicate scan、largest-file scan 和 ignore-rule review。产物分三类：

- **DELETE**：编译器/cache/runtime 自动生成，删除后由标准命令可重建且无证据价值。
- **KEEP**：产品、fixture、模型、迁移/QA/评测证据或架构明确保留。
- **INVESTIGATE**：引用/入口/生成来源不确定，实施前补证据。

禁止直接执行 `git clean -fdx`；删除使用逐路径 allowlist，并在删除前验证路径位于 fresh ForgePilot clone。

### Track B — Executable correctness

优先修复已经证实的执行断链（smoke cookie/CSRF、seed admin、路径依赖），然后对 scripts/API/CI 的生产方真实输出与消费方进行契约测试。脚本共享 cookie/CSRF helper 或保持单一实现，避免再次读取 JSON token。

### Track C — Production/security

按信任边界逐层审计：browser/Nginx → Spring Security → project authorization → SCM/webhook → Agent/outbox/MQ → sandbox → DB/observability。配置采用 fail-fast，不允许生产弱默认值。供应链以现有 CI/trivy/npm 门禁为单源，不新建平行口径。

### Track D — Full code quality

按模块执行静态/动态检查，findings 按 severity + evidence 管理。删除死代码前核对 Spring annotations、JPA reflection、Jackson records、Vue router/dynamic imports、CLI entrypoints 和 shell/API consumers。

## 2. Known fixes

1. Remove tracked `model-service/tests/__pycache__/...pyc`。
2. Add global `**/__pycache__/`, `*.py[cod]`, `.pytest_cache/` ignore protection。
3. Refactor smoke script to a `WebRequestSession`, CSRF bootstrap, rotated token reread, HttpOnly auth cookie；不传 Bearer。
4. `verify-local` smoke child explicitly seeds admin via environment and never writes credentials to disk/logs。
5. `scan-package-deps.py`: repository-relative default plus `--root`/environment override；preserve existing report semantics。
6. Compose requires `GRAFANA_ADMIN_PASSWORD`; `.env.example` and deployment docs align。
7. User-facing verification labels use ForgePilot; internal IDs remain unchanged unless contract owner explicitly approves。

## 3. Verification matrix

| Area | Commands/evidence |
|---|---|
| Hygiene | tracked-pattern scanner, ignored dry-run, duplicate classification, `git diff --check` |
| Backend | `mvn -s .mvn/settings.xml verify`, focused security/contract tests |
| Sandbox | `mvn -s ../backend/.mvn/settings.xml verify` |
| Frontend | `npm ci`, `npm audit --audit-level=high`, `npm test`, `npm run build` |
| Python | clean venv or available interpreter: `pytest`, requirements/trivy scan |
| Evaluation | corpus validation + `score.py --selftest` |
| Scripts | PowerShell parser, Python compile/tests, shell syntax where Bash exists |
| Edge/deploy | `deploy/test-nginx-headers.sh`, compose config, secret preflight; Docker execution when available |
| Security | CI supply-chain/trivy or documented unavailable prerequisite |

## 4. Rollback and preservation

- Cleanup and functional fixes are separated into reviewable commits.
- Demo/evaluation/Trellis evidence directories are protected by explicit path denylist in cleanup scripts/checklists.
- Production config tightening includes `.env.example` and docs in the same commit.
- If a deletion breaks a referenced path or test, restore from Git and reclassify as KEEP/INVESTIGATE rather than weakening tests.

## 5. Final report

Task research will contain:

- `repository-hygiene.md`
- `production-findings.md`
- `deletion-manifest.md`
- `verification.md`

The final conclusion distinguishes verified production-ready areas, environment-blocked areas, and remaining accepted risks.
