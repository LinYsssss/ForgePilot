# Quality Guidelines

> Code quality standards for frontend development.

---

## Overview

<!--
Document your project's quality standards here.

Questions to answer:
- What patterns are forbidden?
- What linting rules do you enforce?
- What are your testing requirements?
- What code review standards apply?
-->

(To be filled by the team)

---

## Forbidden Patterns

<!-- Patterns that should never be used and why -->

### 依赖源纪律:lockfile 只允许官方 registry(r3/F-06 实证)

**What**:`package-lock.json` 的全部 `"resolved"` URL 必须指向 `https://registry.npmjs.org`;禁止镜像源(npmmirror 等)进入 lockfile。CI 有源门禁(`ci.yml` 的 lockfile 源检查步骤,只匹配 `"resolved"` 行,不误伤 `funding` 元数据 URL)。

**Why**:镜像源 lockfile 在 npm≥12 直接拒装(EALLOWREMOTE),且供应链不可审计。审计 F-06 时 94% 条目指向 npmmirror,即为此病。

**Gotcha — clean-room 重建**:仅删 `package-lock.json` 后 `npm install --registry=https://registry.npmjs.org/` 是**不够**的:npm 会复用 `node_modules/.package-lock.json` 里的旧镜像元数据,并丢失平台变体包。必须连 `node_modules` 一起删再装(容器内执行天然干净)。重建后三项验证:
1. `grep '"resolved"' package-lock.json | grep -vc 'registry.npmjs.org'` → 0
2. `package.json` 的 `overrides`(如 nanoid ≥3.3.17 安全钉版)仍生效
3. 容器内 `npm ci && npm test && npm run build` 全绿

**新增可疑包核对法**:对 lockfile 新出现的包,取官方 registry 元数据的 `dist.integrity` 与 lockfile integrity 哈希逐字节比对(r3 曾以此排除 `@napi-rs/lzma-linux-x64-gnu` 的投毒嫌疑——rollup 4.62.4 官方声明的 optionalDependency,哈希一致)。

---

## Required Patterns

<!-- Patterns that must always be used -->

(To be filled by the team)

---

## Testing Requirements

<!-- What level of testing is expected -->

(To be filled by the team)

---

## Code Review Checklist

<!-- What reviewers should check -->

(To be filled by the team)
