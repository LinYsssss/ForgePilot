# demo-repos 演示素材改造 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `demo-repos/` 在服务器完整版部署下支撑一次完整的审查与 PR 演示，且推送到 GitHub/GitLab 后素材不丢失。

**Architecture:** 演示仓库的源码以 baseline（main 分支）状态存放在主仓库工作区；PR 分支的变更以纯文本 patch 文件保存在 `demo-repos/patches/`；一个确定性重建脚本在任意机器上从这两者还原出带分支的本地 git 仓库，commit SHA 完全一致。知识库分三层：仓库专属、跨仓库通用规范、故意无关的干扰文档。

**Tech Stack:** Bash / PowerShell 脚本、git plumbing、JDK 17 `javac`、Python `compileall`、Node `--check`、Markdown。

## Global Constraints

- **43 条缺陷一条不增、一条不减**：M1~M10（10 条）、P1~P15（15 条）、T1~T18（18 条）。本计划不改缺陷语义、不改所在方法、不加框架。
- **演示仓库零外部依赖**：不引入 Spring Boot、MyBatis、pytest、jest。仓库必须 clone 下来无需联网即可通过语法与编译校验。
- **不构建沙箱工具镜像**：`ValidatingPatchStepExecutor.java:163-169` 已证实沙箱不可用时 Agent 链路会 `ADVANCE` 到 `PUBLISHING_RESULT` 并带 `approvable: false`，不会卡住。
- **行尾必须是 LF**：本机 `core.autocrlf=true` 且无 `.gitattributes`。CRLF 会改变 blob 哈希从而破坏 SHA 确定性。每个演示仓库必须有 `.gitattributes`，重建脚本必须显式关闭行尾转换。
- **提交信息不得包含 AI/Claude 署名或生成标记。**
- 所有新增 Markdown 使用 LF 行尾、UTF-8 无 BOM。

## 关键事实（实施前必读）

已实测确认，不要重新推断：

| 事实 | 证据 |
|---|---|
| `mall-order-service` 的 **main 分支能编译**，feature 分支不能 | `git archive main` 到临时目录后 `javac` EXIT=0；工作区（feature 状态）EXIT=1 |
| 编译失败的原因是 PR 新增的 `PromotionShipService` 调用了 `OrderMapper` 上不存在的方法 | `selectByActivity` / `updateStatus` / `selectById` / `updatePaidAmount` / `selectBySql`，以及 `Order.getAmount()` |
| **修复必须落在 baseline 上**，否则 patch 应用后仍编译不过 | 同上 |
| `payment-settlement-service` 源码 `javac` EXIT=0，0 错误 | 只缺 `pom.xml` |
| `tenant-user-center` 通过 `python -m compileall` 与 `node --check` | 只缺 `pyproject.toml` / `package.json` |
| 三个仓库的 feature 分支**全是纯新增文件** | `git diff --stat main..feature` 显示 100% insertions |
| `com.example.mallorder` 包无任何外部引用，可安全删除 | `grep -rn mallorder src/` 只命中包自身 |
| 主仓库工作区当前是 **feature 分支状态**（三个仓库都 checkout 在 feature 上） | `git branch` 输出 `*` 在 feature 上 |

**feature 分支独有文件清单**（Task 5 要用）：

```
mall-order-service:
  src/main/java/com/example/mall/controller/PromotionController.java
  src/main/java/com/example/mall/service/PromotionShipService.java

payment-settlement-service:
  src/main/java/com/example/settlement/controller/JsonSupport.java
  src/main/java/com/example/settlement/controller/PayoutCallbackController.java
  src/main/java/com/example/settlement/controller/RefundController.java
  src/main/java/com/example/settlement/controller/SettlementStatusUpdater.java
  src/main/java/com/example/settlement/repository/MerchantQueryRepository.java
  src/main/java/com/example/settlement/repository/PayoutCallbackLogRepository.java
  src/main/java/com/example/settlement/repository/RawJdbc.java
  src/main/java/com/example/settlement/service/InstantSettlementService.java
  src/main/java/com/example/settlement/service/RefundService.java

tenant-user-center:
  src/app/ops_console.py
  web/ops-console.js
```

## 文件结构

| 文件 | 职责 |
|---|---|
| `scripts/verify-demo-repos.sh` | 唯一的验证入口：语法/编译检查 + SHA 确定性检查。是本计划所有任务的测试 |
| `scripts/init-demo-repos.sh` | 从 baseline + patches 确定性重建三个本地 git 仓库 |
| `scripts/init-demo-repos.ps1` | 同上的 Windows 版 |
| `scripts/demo-repos-expected-sha.txt` | 预期 SHA 清单，供 `--verify` 比对 |
| `demo-repos/patches/<repo>/feature-<name>.patch` | PR 分支的完整 diff |
| `demo-repos/<repo>/.gitattributes` | 强制 LF，保证 SHA 跨平台一致 |
| `demo-repos/knowledge-shared/*.md` | 5 份跨仓库通用规范 |
| `demo-repos/knowledge-noise/*.md` | 3 份干扰文档 |
| `demo-repos/README.md` | 素材总览、上手步骤、诚实边界 |

---

### Task 1: 建立验证脚本（此时应当失败）

先写验证，再修代码。这个脚本是后续所有任务的测试。

**Files:**
- Create: `scripts/verify-demo-repos.sh`

**Interfaces:**
- Produces: 可执行脚本 `scripts/verify-demo-repos.sh`，无参数运行；全部检查通过时 exit 0，任一失败 exit 1 并打印失败项。后续 Task 2/3/4/6 都靠它验收。

- [ ] **Step 1: 写验证脚本**

创建 `scripts/verify-demo-repos.sh`：

```bash
#!/usr/bin/env bash
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO="$ROOT_DIR/demo-repos"
FAILED=0

fail() { echo "FAIL: $*" >&2; FAILED=1; }
pass() { echo "ok  : $*"; }

check_java() {
  local repo="$1"
  local out
  out="$(mktemp -d)"
  local sources
  sources="$(find "$DEMO/$repo/src" -name '*.java' 2>/dev/null)"
  if [ -z "$sources" ]; then
    fail "$repo: no java sources found"
    rm -rf "$out"
    return
  fi
  # shellcheck disable=SC2086
  if javac -encoding UTF-8 -d "$out" $sources >"$out/log" 2>&1; then
    pass "$repo: javac"
  else
    fail "$repo: javac"
    head -20 "$out/log" >&2
  fi
  rm -rf "$out"
}

check_build_descriptor() {
  local repo="$1" file="$2"
  if [ -f "$DEMO/$repo/$file" ]; then
    pass "$repo: $file present"
  else
    fail "$repo: $file missing"
  fi
}

echo "--- 编译与语法 ---"
check_java mall-order-service
check_java payment-settlement-service
check_build_descriptor mall-order-service pom.xml
check_build_descriptor payment-settlement-service pom.xml
check_build_descriptor tenant-user-center pyproject.toml
check_build_descriptor tenant-user-center package.json

if python -m compileall -q "$DEMO/tenant-user-center/src" >/dev/null 2>&1; then
  pass "tenant-user-center: python compileall"
else
  fail "tenant-user-center: python compileall"
fi

for js in "$DEMO"/tenant-user-center/web/*.js; do
  [ -e "$js" ] || continue
  if node --check "$js" >/dev/null 2>&1; then
    pass "tenant-user-center: node --check $(basename "$js")"
  else
    fail "tenant-user-center: node --check $(basename "$js")"
  fi
done

echo "--- 重复类 ---"
if [ -d "$DEMO/mall-order-service/src/main/java/com/example/mallorder" ]; then
  fail "mall-order-service: duplicate package com.example.mallorder still present"
else
  pass "mall-order-service: no duplicate package"
fi

exit "$FAILED"
```

- [ ] **Step 2: 赋可执行权限并运行，确认失败**

```bash
chmod +x scripts/verify-demo-repos.sh
bash scripts/verify-demo-repos.sh
```

预期：exit 1。失败项应包含
- `FAIL: mall-order-service: javac`（`OrderMapper` 缺方法）
- `FAIL: payment-settlement-service: pom.xml missing`
- `FAIL: tenant-user-center: pyproject.toml missing`
- `FAIL: tenant-user-center: package.json missing`
- `FAIL: mall-order-service: duplicate package com.example.mallorder still present`

- [ ] **Step 3: 提交**

```bash
git add scripts/verify-demo-repos.sh
git commit -m "chore(demo): add a verification entry point for the demo repositories"
```

---

### Task 2: 修复 mall-order-service 基线

**Files:**
- Modify: `demo-repos/mall-order-service/src/main/java/com/example/mall/mapper/OrderMapper.java`
- Modify: `demo-repos/mall-order-service/src/main/java/com/example/mall/entity/Order.java`
- Delete: `demo-repos/mall-order-service/src/main/java/com/example/mallorder/Order.java`
- Delete: `demo-repos/mall-order-service/src/main/java/com/example/mallorder/OrderService.java`

**Interfaces:**
- Consumes: `scripts/verify-demo-repos.sh`（Task 1）
- Produces: `OrderMapper` 具备 `selectByActivity(Long): List<Order>`、`updateStatus(Long, String): void`、`selectById(Long): Order`、`updatePaidAmount(Long, long): void`、`selectBySql(String): List<Order>`；`Order` 具备 `getUserId(): Long`、`getAmount(): long`、`getPaidAmount(): long`、`getReceiverPhone(): String`、`getReceiverAddress(): String`、`getShippedAt(): String`，以及三个 setter `setStatus(String)`、`setPaidAmount(long)`、`setShippedAt(String)`。其余字段只读，没有 setter 也没有构造器——Task 5 的 patch 只调用 getter 与 `setStatus`，不要去找不存在的写入口。

字段名严格对齐 `demo-repos/mall-order-service/docs/db-schema.md` 的 `orders` 表。`getAmount()` 返回 `long`（文档规定金额为 `bigint` 存「分」）——这正是 M4「金额计算用了 double」能成立的前提。

- [ ] **Step 1: 运行验证，确认 mall 编译失败**

```bash
bash scripts/verify-demo-repos.sh 2>&1 | grep mall-order-service
```

预期：`FAIL: mall-order-service: javac`，日志中出现 `找不到符号` / `cannot find symbol`。

- [ ] **Step 2: 补全 Order 实体**

`demo-repos/mall-order-service/src/main/java/com/example/mall/entity/Order.java` 整体替换为：

```java
package com.example.mall.entity;

/** 订单实体，字段与 docs/db-schema.md 的 orders 表对应。金额单位为分。 */
public class Order {
    private Long id;
    private Long userId;
    private String status;
    private String payStatus;
    private long amount;
    private long paidAmount;
    private String receiverPhone;
    private String receiverAddress;
    private String shippedAt;

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getStatus() {
        return status;
    }

    public String getPayStatus() {
        return payStatus;
    }

    public long getAmount() {
        return amount;
    }

    public long getPaidAmount() {
        return paidAmount;
    }

    public String getReceiverPhone() {
        return receiverPhone;
    }

    public String getReceiverAddress() {
        return receiverAddress;
    }

    public String getShippedAt() {
        return shippedAt;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setPaidAmount(long paidAmount) {
        this.paidAmount = paidAmount;
    }

    public void setShippedAt(String shippedAt) {
        this.shippedAt = shippedAt;
    }
}
```

- [ ] **Step 3: 补全 OrderMapper**

`demo-repos/mall-order-service/src/main/java/com/example/mall/mapper/OrderMapper.java` 整体替换为：

```java
package com.example.mall.mapper;

import com.example.mall.entity.Order;
import java.util.ArrayList;
import java.util.List;

/** 订单数据访问。演示用实现，不连接真实数据库。 */
public class OrderMapper {

    public Order findById(Long id) {
        return new Order();
    }

    public Order selectById(Long id) {
        return new Order();
    }

    public List<Order> selectByActivity(Long activityId) {
        return new ArrayList<>();
    }

    public void updateStatus(Long orderId, String status) {
        // 演示用空实现
    }

    public void updatePaidAmount(Long orderId, long paidAmount) {
        // 演示用空实现
    }

    public List<Order> selectBySql(String sql) {
        return new ArrayList<>();
    }

    public String searchByKeyword(String keyword) {
        return "select * from orders where username like '%" + keyword + "%'";
    }
}
```

保留 `searchByKeyword` 原样——它本身是一处 SQL 拼接演示点，不属于本次修复范围。

- [ ] **Step 4: 删除重复包**

```bash
rm -rf demo-repos/mall-order-service/src/main/java/com/example/mallorder
```

- [ ] **Step 5: 运行验证，确认 mall 部分通过**

```bash
bash scripts/verify-demo-repos.sh 2>&1 | grep mall-order-service
```

预期：
```
ok  : mall-order-service: javac
ok  : mall-order-service: pom.xml present
ok  : mall-order-service: no duplicate package
```

- [ ] **Step 6: 提交**

```bash
git add demo-repos/mall-order-service
git commit -m "fix(demo): complete the mall order baseline so both branches compile"
```

---

### Task 3: 补齐另外两个仓库的构建描述

**Files:**
- Create: `demo-repos/payment-settlement-service/pom.xml`
- Create: `demo-repos/tenant-user-center/pyproject.toml`
- Create: `demo-repos/tenant-user-center/package.json`

**Interfaces:**
- Consumes: `scripts/verify-demo-repos.sh`（Task 1）
- Produces: 三个仓库均可被构建工具识别。无后续任务依赖其内容。

- [ ] **Step 1: 运行验证，确认三项失败**

```bash
bash scripts/verify-demo-repos.sh 2>&1 | grep -E "pom.xml missing|pyproject.toml missing|package.json missing"
```

预期三行 FAIL。

- [ ] **Step 2: 写 payment 的 pom.xml**

结构与 `demo-repos/mall-order-service/pom.xml` 保持一致，无外部依赖：

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>payment-settlement-service</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
</project>
```

- [ ] **Step 3: 写 tenant 的 pyproject.toml**

依赖只作元数据声明，不需要真实安装：

```toml
[project]
name = "tenant-user-center"
version = "0.1.0"
description = "多租户用户中心演示仓库，用于 RepoSage 代码审查演示"
requires-python = ">=3.11"
dependencies = [
    "fastapi>=0.110",
    "pyjwt>=2.8",
]

[build-system]
requires = ["setuptools>=68"]
build-backend = "setuptools.build_meta"

[tool.setuptools]
packages = ["app"]
package-dir = { "" = "src" }
```

- [ ] **Step 4: 写 tenant 的 package.json**

```json
{
  "name": "tenant-user-center-web",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "description": "多租户用户中心前端演示代码",
  "main": "web/user-admin.js"
}
```

- [ ] **Step 5: 运行完整验证，确认全绿**

```bash
bash scripts/verify-demo-repos.sh
echo "EXIT=$?"
```

预期：`EXIT=0`，所有行都是 `ok  :`。

- [ ] **Step 6: 提交**

```bash
git add demo-repos/payment-settlement-service/pom.xml demo-repos/tenant-user-center/pyproject.toml demo-repos/tenant-user-center/package.json
git commit -m "chore(demo): add build descriptors so each demo repo is recognizable"
```

---

### Task 4: 加入行尾控制

SHA 确定性的前提。本机 `core.autocrlf=true` 且无 `.gitattributes`，若某台机器 checkout 出 CRLF 而内层仓库不做归一，blob 哈希会变，重建出的 SHA 就对不上。

**Files:**
- Create: `.gitattributes`（主仓库根）
- Create: `demo-repos/mall-order-service/.gitattributes`
- Create: `demo-repos/payment-settlement-service/.gitattributes`
- Create: `demo-repos/tenant-user-center/.gitattributes`

**Interfaces:**
- Produces: 三个演示仓库各有一个 `.gitattributes`，内容为 `* -text`。Task 6 的重建脚本依赖它们已存在于 baseline。

- [ ] **Step 1: 确认当前存储状态**

```bash
git ls-files --eol demo-repos/mall-order-service/ | head -3
```

预期：`i/lf w/lf attr/`。若出现 `w/crlf`，说明工作区已被转换，需先 `git rm --cached -r demo-repos && git checkout demo-repos` 归一后再继续。

- [ ] **Step 2: 主仓库根 .gitattributes**

创建 `.gitattributes`：

```gitattributes
# 演示仓库的文件参与 SHA 确定性重建，checkout 必须始终产出 LF
# text=auto 会自动判别二进制，不会损坏日后可能加入的图片等素材
demo-repos/** text=auto eol=lf

# 本机 core.autocrlf=true。若 shell 脚本被检出为 CRLF，msys bash 会在
# `set -uo pipefail\r` 直接报错，而这些脚本是后续任务的验收入口。
*.sh text eol=lf
```

- [ ] **Step 3: 每个演示仓库各加一个 .gitattributes**

三个文件内容完全相同：

```gitattributes
* text=auto eol=lf
```

`text=auto` 让 git 自动判别文本与二进制，`eol=lf` 让文本在 **check-in 与 checkout 两个方向**都归一为 LF。

不要用 `* -text`。`-text` 只保 checkout：若有人把某个文件写成 CRLF 再提交，blob 哈希会变，而 `-text` 同时抑制了 git 的 CRLF 警告，这种漂移是**静默的**——恰好会击穿 Task 6 的 SHA 确定性。

```bash
for r in mall-order-service payment-settlement-service tenant-user-center; do
  printf '* text=auto eol=lf\n' > "demo-repos/$r/.gitattributes"
done
```

- [ ] **Step 4: 顺手修三处一致性问题**

这三处由 Task 3 的审查发现，都属计划自身的不精确，在此一并修正。

**(a) 让验证脚本不再产生 `__pycache__`**

`scripts/verify-demo-repos.sh` 里的 `python -m compileall` 每次运行都会在 `demo-repos/tenant-user-center/src/app/` 下重新生成 `__pycache__/`，持续弄脏工作区。Task 6 的确定性重建要求工作区无副产物，所以从源头不产生优于事后忽略。

**注意：`PYTHONDONTWRITEBYTECODE=1` 对 `compileall` 无效**（已实测）。该变量只设 `sys.dont_write_bytecode` 约束 import 系统，而 `compileall` 直接调用 `py_compile.compile()` 写盘，不查这个标志。必须改用不落盘的内存编译：

```bash
PY_SYNTAX_CHECK='import pathlib, sys
paths = sorted(pathlib.Path(sys.argv[1]).rglob("*.py"))
if not paths:
    sys.exit("no python sources found")
for p in paths:
    compile(p.read_bytes(), str(p), "exec")'

if python -c "$PY_SYNTAX_CHECK" "$DEMO/tenant-user-center/src" >/dev/null 2>&1; then
  pass "tenant-user-center: python syntax"
else
  fail "tenant-user-center: python syntax"
fi
```

检查项标签随之由 `python compileall` 变为 `python syntax`。顺带补上了原脚本缺失的「无源码文件时判失败」护栏。

改完后删掉已有的残留：

```bash
rm -rf demo-repos/tenant-user-center/src/app/__pycache__
```

**(b) 两个 pom 真正对齐**

`demo-repos/payment-settlement-service/pom.xml` 比 `demo-repos/mall-order-service/pom.xml` 多一行编码声明。给 mall 的 pom 在 `</properties>` 之前补上同一行：

```xml
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
```

**(c) pyproject 补上实际用到的依赖**

`demo-repos/tenant-user-center/src/app/auth.py` 导入了 `bcrypt`，但 `pyproject.toml` 只声明了 fastapi 与 pyjwt。在 `dependencies` 列表末尾补一行：

```toml
    "bcrypt>=4.1",
```

**(d) Python 检查失败时输出诊断**

同一脚本的 `check_java` 失败时会 `head -20 "$out/log" >&2`，Python 这条却把 SyntaxError 的位置信息全吞了，诊断能力不对称。Task 6 重建失败时会用得上。把 (a) 的调用改为先捕获输出、失败时打印：

```bash
py_log="$(mktemp)"
if python -c "$PY_SYNTAX_CHECK" "$DEMO/tenant-user-center/src" >"$py_log" 2>&1; then
  pass "tenant-user-center: python syntax"
else
  fail "tenant-user-center: python syntax"
  head -20 "$py_log" >&2
fi
rm -f "$py_log"
```

- [ ] **Step 5: 验证仍全绿**

```bash
bash scripts/verify-demo-repos.sh
echo "EXIT=$?"
```

预期：`EXIT=0`。

- [ ] **Step 6: 提交**

```bash
git add .gitattributes demo-repos/*/.gitattributes scripts/verify-demo-repos.sh demo-repos/mall-order-service/pom.xml demo-repos/tenant-user-center/pyproject.toml
git commit -m "chore(demo): pin line endings so rebuilt commit hashes stay stable"
```

---

### Task 5: 导出 PR 分支 patch，工作区回落到 baseline

这是 PR 演示的核心。执行后主仓库工作区不再包含 feature 分支的文件——它们改由 patch 承载。

**Files:**
- Create: `demo-repos/patches/mall-order-service/feature-promotion-batch-ship.patch`
- Create: `demo-repos/patches/payment-settlement-service/feature-instant-settlement.patch`
- Create: `demo-repos/patches/tenant-user-center/feature-ops-console.patch`
- Delete: 13 个 feature 独有文件（清单见「关键事实」一节）
- Delete: `demo-repos/mall-order-service/.git`、`demo-repos/payment-settlement-service/.git`、`demo-repos/tenant-user-center/.git`

**Interfaces:**
- Consumes: Task 2 补全的 `OrderMapper` / `Order` 签名——patch 中的 `PromotionShipService` 调用它们
- Produces: 三个 patch 文件。Task 6 的重建脚本按 `<repo>/feature-<name>.patch` 路径查找它们。

- [ ] **Step 1: 导出三个 patch**

```bash
mkdir -p demo-repos/patches/mall-order-service \
         demo-repos/patches/payment-settlement-service \
         demo-repos/patches/tenant-user-center

git -C demo-repos/mall-order-service diff main..feature/promotion-batch-ship \
  > demo-repos/patches/mall-order-service/feature-promotion-batch-ship.patch
git -C demo-repos/payment-settlement-service diff main..feature/instant-settlement \
  > demo-repos/patches/payment-settlement-service/feature-instant-settlement.patch
git -C demo-repos/tenant-user-center diff main..feature/ops-console \
  > demo-repos/patches/tenant-user-center/feature-ops-console.patch
```

- [ ] **Step 2: 确认 patch 非空且是纯新增**

```bash
for p in demo-repos/patches/*/*.patch; do
  echo "$p: $(grep -c '^new file mode' "$p") new files, $(grep -c '^-[^-]' "$p") deletions"
done
```

预期：mall 2 new / 0 deletions，payment 9 new / 0 deletions，tenant 2 new / 0 deletions。任一 deletions 非 0 就停下来排查。

- [ ] **Step 3: 删除 feature 独有文件与内层 .git**

```bash
rm -f demo-repos/mall-order-service/src/main/java/com/example/mall/controller/PromotionController.java \
      demo-repos/mall-order-service/src/main/java/com/example/mall/service/PromotionShipService.java
rm -f demo-repos/payment-settlement-service/src/main/java/com/example/settlement/controller/JsonSupport.java \
      demo-repos/payment-settlement-service/src/main/java/com/example/settlement/controller/PayoutCallbackController.java \
      demo-repos/payment-settlement-service/src/main/java/com/example/settlement/controller/RefundController.java \
      demo-repos/payment-settlement-service/src/main/java/com/example/settlement/controller/SettlementStatusUpdater.java \
      demo-repos/payment-settlement-service/src/main/java/com/example/settlement/repository/MerchantQueryRepository.java \
      demo-repos/payment-settlement-service/src/main/java/com/example/settlement/repository/PayoutCallbackLogRepository.java \
      demo-repos/payment-settlement-service/src/main/java/com/example/settlement/repository/RawJdbc.java \
      demo-repos/payment-settlement-service/src/main/java/com/example/settlement/service/InstantSettlementService.java \
      demo-repos/payment-settlement-service/src/main/java/com/example/settlement/service/RefundService.java
rm -f demo-repos/tenant-user-center/src/app/ops_console.py \
      demo-repos/tenant-user-center/web/ops-console.js
rm -rf demo-repos/mall-order-service/.git \
       demo-repos/payment-settlement-service/.git \
       demo-repos/tenant-user-center/.git
```

- [ ] **Step 4: 验证 baseline 仍全绿**

```bash
bash scripts/verify-demo-repos.sh
echo "EXIT=$?"
```

预期：`EXIT=0`。baseline 现在不含 PR 代码，编译应当更干净。

- [ ] **Step 5: 验证 patch 应用后仍能编译**

这一步确认 Task 2 的签名补全是对的。

```bash
PATCH="$(pwd)/demo-repos/patches/mall-order-service/feature-promotion-batch-ship.patch"
rm -rf /tmp/patchcheck && cp -r demo-repos/mall-order-service /tmp/patchcheck
git -C /tmp/patchcheck init -q -b main
git -C /tmp/patchcheck add -A
git -C /tmp/patchcheck -c user.name=t -c user.email=t@t commit -qm base
git -C /tmp/patchcheck apply "$PATCH"
javac -encoding UTF-8 -d /tmp/patchout $(find /tmp/patchcheck/src -name '*.java')
echo "PATCHED_EXIT=$?"
rm -rf /tmp/patchcheck /tmp/patchout
```

预期：`PATCHED_EXIT=0`。若失败，说明 `OrderMapper` 或 `Order` 的签名与 patch 中的调用不匹配，回到 Task 2 修正。

- [ ] **Step 6: 提交**

```bash
git add -A demo-repos
git commit -m "chore(demo): move PR branch changes into text patches and reset repos to baseline"
```

---

### Task 6: 确定性重建脚本

**Files:**
- Create: `scripts/init-demo-repos.sh`
- Create: `scripts/init-demo-repos.ps1`
- Create: `scripts/demo-repos-expected-sha.txt`
- Delete: `scripts/init-demo-repo.sh`（被取代）
- Modify: `scripts/verify-demo-repos.sh`（追加 SHA 校验段）

**Interfaces:**
- Consumes: `demo-repos/patches/<repo>/feature-<name>.patch`（Task 5）、`demo-repos/<repo>/.gitattributes`（Task 4）
- Produces: `scripts/init-demo-repos.sh` 支持无参数（重建）与 `--verify`（重建后比对 SHA）。`scripts/demo-repos-expected-sha.txt` 每行格式 `<repo> <ref> <sha>`。

每个仓库重建为 3 个 commit：main 上 2 个（源码、知识文档），feature 分支 1 个。这样提交浏览（M3）有内容可看，PR diff（M4）也成立。

- [ ] **Step 1: 写 bash 重建脚本**

创建 `scripts/init-demo-repos.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO="$ROOT_DIR/demo-repos"
PATCHES="$DEMO/patches"
EXPECTED="$ROOT_DIR/scripts/demo-repos-expected-sha.txt"
VERIFY=0
[ "${1:-}" = "--verify" ] && VERIFY=1

export GIT_AUTHOR_NAME="RepoSage Demo"
export GIT_AUTHOR_EMAIL="demo@reposage.local"
export GIT_COMMITTER_NAME="RepoSage Demo"
export GIT_COMMITTER_EMAIL="demo@reposage.local"

# repo|feature-branch|patch-file
REPOS=(
  "mall-order-service|feature/promotion-batch-ship|feature-promotion-batch-ship.patch"
  "payment-settlement-service|feature/instant-settlement|feature-instant-settlement.patch"
  "tenant-user-center|feature/ops-console|feature-ops-console.patch"
)

commit_at() {
  local stamp="$1" message="$2"
  GIT_AUTHOR_DATE="$stamp" GIT_COMMITTER_DATE="$stamp" \
    git commit -q --no-gpg-sign -m "$message"
}

build_repo() {
  local repo="$1" branch="$2" patch="$3"
  local dir="$DEMO/$repo"

  [ -d "$dir" ] || { echo "missing demo repo: $dir" >&2; exit 1; }
  [ -f "$PATCHES/$repo/$patch" ] || { echo "missing patch: $PATCHES/$repo/$patch" >&2; exit 1; }

  if [ -e "$dir/.git" ]; then
    echo "already initialized, skipping: $repo"
    return 0
  fi

  git -C "$dir" init -q -b main
  git -C "$dir" config core.autocrlf false
  git -C "$dir" config core.eol lf
  git -C "$dir" config commit.gpgsign false

  # commit 1: 源码与构建描述
  git -C "$dir" add -A ':!docs' ':!README.md'
  ( cd "$dir" && commit_at "2026-01-15T10:00:00+08:00" "feat: initial service implementation" )

  # commit 2: 知识文档
  git -C "$dir" add -A
  ( cd "$dir" && commit_at "2026-01-15T11:00:00+08:00" "docs: add knowledge base for review context" )

  # feature 分支
  git -C "$dir" switch -q -c "$branch"
  git -C "$dir" apply "$PATCHES/$repo/$patch"
  git -C "$dir" add -A
  ( cd "$dir" && commit_at "2026-01-15T14:00:00+08:00" "feat: implement the new feature for review" )
  git -C "$dir" switch -q main

  echo "initialized: $repo"
}

for entry in "${REPOS[@]}"; do
  IFS='|' read -r repo branch patch <<< "$entry"
  build_repo "$repo" "$branch" "$patch"
done

if [ "$VERIFY" = "1" ]; then
  [ -f "$EXPECTED" ] || { echo "expected sha list not found: $EXPECTED" >&2; exit 1; }
  status=0
  while read -r repo ref sha; do
    [ -z "${repo:-}" ] && continue
    actual="$(git -C "$DEMO/$repo" rev-parse "$ref")"
    if [ "$actual" = "$sha" ]; then
      echo "ok  : $repo $ref $sha"
    else
      echo "FAIL: $repo $ref expected $sha but got $actual" >&2
      status=1
    fi
  done < "$EXPECTED"
  exit "$status"
fi
```

- [ ] **Step 2: 首次运行，生成 SHA 清单**

```bash
chmod +x scripts/init-demo-repos.sh
rm -rf demo-repos/*/.git
bash scripts/init-demo-repos.sh
{
  for r in mall-order-service payment-settlement-service tenant-user-center; do
    echo "$r main $(git -C demo-repos/$r rev-parse main)"
  done
  echo "mall-order-service refs/heads/feature/promotion-batch-ship $(git -C demo-repos/mall-order-service rev-parse feature/promotion-batch-ship)"
  echo "payment-settlement-service refs/heads/feature/instant-settlement $(git -C demo-repos/payment-settlement-service rev-parse feature/instant-settlement)"
  echo "tenant-user-center refs/heads/feature/ops-console $(git -C demo-repos/tenant-user-center rev-parse feature/ops-console)"
} > scripts/demo-repos-expected-sha.txt
cat scripts/demo-repos-expected-sha.txt
```

- [ ] **Step 3: 删掉重建，验证 SHA 可复现**

这是确定性的实证。

```bash
rm -rf demo-repos/*/.git
bash scripts/init-demo-repos.sh --verify
echo "EXIT=$?"
```

预期：`EXIT=0`，6 行全部 `ok  :`。若出现 FAIL，检查是否有未被 `.gitattributes` 覆盖的文件，或 `commit_at` 的时间戳未生效。

- [ ] **Step 4: 把 SHA 校验并入验证脚本**

在 `scripts/verify-demo-repos.sh` 的 `exit "$FAILED"` 之前插入：

```bash
echo "--- SHA 确定性 ---"
if [ -f "$ROOT_DIR/scripts/demo-repos-expected-sha.txt" ]; then
  while read -r repo ref sha; do
    [ -z "${repo:-}" ] && continue
    if [ ! -d "$DEMO/$repo/.git" ]; then
      fail "$repo: not initialized, run scripts/init-demo-repos.sh"
      continue
    fi
    actual="$(git -C "$DEMO/$repo" rev-parse "$ref" 2>/dev/null || echo missing)"
    if [ "$actual" = "$sha" ]; then
      pass "$repo $ref"
    else
      fail "$repo $ref expected $sha but got $actual"
    fi
  done < "$ROOT_DIR/scripts/demo-repos-expected-sha.txt"
else
  fail "scripts/demo-repos-expected-sha.txt missing"
fi
```

- [ ] **Step 5: 写 PowerShell 版本**

创建 `scripts/init-demo-repos.ps1`，行为与 bash 版一致：

```powershell
#Requires -Version 7
param([switch]$Verify)
$ErrorActionPreference = 'Stop'

$RootDir = Split-Path -Parent $PSScriptRoot
$Demo = Join-Path $RootDir 'demo-repos'
$Patches = Join-Path $Demo 'patches'
$Expected = Join-Path $RootDir 'scripts/demo-repos-expected-sha.txt'

$env:GIT_AUTHOR_NAME = 'RepoSage Demo'
$env:GIT_AUTHOR_EMAIL = 'demo@reposage.local'
$env:GIT_COMMITTER_NAME = 'RepoSage Demo'
$env:GIT_COMMITTER_EMAIL = 'demo@reposage.local'

$Repos = @(
  @{ Name = 'mall-order-service'; Branch = 'feature/promotion-batch-ship'; Patch = 'feature-promotion-batch-ship.patch' },
  @{ Name = 'payment-settlement-service'; Branch = 'feature/instant-settlement'; Patch = 'feature-instant-settlement.patch' },
  @{ Name = 'tenant-user-center'; Branch = 'feature/ops-console'; Patch = 'feature-ops-console.patch' }
)

function Invoke-CommitAt($Dir, $Stamp, $Message) {
  $env:GIT_AUTHOR_DATE = $Stamp
  $env:GIT_COMMITTER_DATE = $Stamp
  git -C $Dir commit -q --no-gpg-sign -m $Message
  Remove-Item Env:GIT_AUTHOR_DATE, Env:GIT_COMMITTER_DATE
}

foreach ($repo in $Repos) {
  $dir = Join-Path $Demo $repo.Name
  $patchFile = Join-Path $Patches (Join-Path $repo.Name $repo.Patch)
  if (-not (Test-Path $dir)) { throw "missing demo repo: $dir" }
  if (-not (Test-Path $patchFile)) { throw "missing patch: $patchFile" }
  if (Test-Path (Join-Path $dir '.git')) {
    Write-Host "already initialized, skipping: $($repo.Name)"
    continue
  }

  git -C $dir init -q -b main
  git -C $dir config core.autocrlf false
  git -C $dir config core.eol lf
  git -C $dir config commit.gpgsign false

  git -C $dir add -A ':!docs' ':!README.md'
  Invoke-CommitAt $dir '2026-01-15T10:00:00+08:00' 'feat: initial service implementation'

  git -C $dir add -A
  Invoke-CommitAt $dir '2026-01-15T11:00:00+08:00' 'docs: add knowledge base for review context'

  git -C $dir switch -q -c $repo.Branch
  git -C $dir apply $patchFile
  git -C $dir add -A
  Invoke-CommitAt $dir '2026-01-15T14:00:00+08:00' 'feat: implement the new feature for review'
  git -C $dir switch -q main

  Write-Host "initialized: $($repo.Name)"
}

if ($Verify) {
  if (-not (Test-Path $Expected)) { throw "expected sha list not found: $Expected" }
  $status = 0
  foreach ($line in Get-Content $Expected) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line -split '\s+'
    $actual = git -C (Join-Path $Demo $parts[0]) rev-parse $parts[1]
    if ($actual -eq $parts[2]) {
      Write-Host "ok  : $($parts[0]) $($parts[1])"
    } else {
      Write-Error "FAIL: $($parts[0]) $($parts[1]) expected $($parts[2]) but got $actual"
      $status = 1
    }
  }
  exit $status
}
```

- [ ] **Step 6: 验证 PowerShell 版产出相同 SHA**

```powershell
Remove-Item -Recurse -Force demo-repos/*/.git
pwsh -File scripts/init-demo-repos.ps1 -Verify
```

预期：6 行 `ok  :`，退出码 0。两个脚本产出同一组 SHA 是跨平台确定性的实证。

- [ ] **Step 7: 删除旧脚本并提交**

```bash
git rm -q scripts/init-demo-repo.sh
git add scripts/init-demo-repos.sh scripts/init-demo-repos.ps1 scripts/demo-repos-expected-sha.txt scripts/verify-demo-repos.sh
git commit -m "feat(demo): rebuild all three demo repos deterministically from patches"
```

---

### Task 7: 跨仓库通用规范文档

**Files:**
- Create: `demo-repos/knowledge-shared/engineering-standards.md`
- Create: `demo-repos/knowledge-shared/security-baseline.md`
- Create: `demo-repos/knowledge-shared/api-design-guide.md`
- Create: `demo-repos/knowledge-shared/data-handling-policy.md`
- Create: `demo-repos/knowledge-shared/code-review-checklist.md`

**Interfaces:**
- Produces: 5 份 Markdown。Task 9 的 README 引用它们的路径；对照表的三档实验第 2 档指的就是这 5 份。

**内容硬约束：只写「是什么」，不写具体数值与业务规则。** 数值与业务规则属于仓库专属文档。若通用规范里出现「费率 0.6%」「最小结算额 100 分」「bcrypt cost ≥ 12」这类具体值，就会抢走 B/C 类缺陷的判定作用，本任务即算失败。

每份文档 1500~2500 字符（与现有专属文档量级一致，保证切片数达标）。

- [ ] **Step 1: engineering-standards.md**

必须覆盖且仅覆盖以下条目，每条一小节，不带具体阈值：
命名约定、异常处理（禁止吞异常、禁止裸 catch）、日志（禁止记录敏感字段、必须带请求标识）、注释（解释为什么而非做什么）、方法长度与圈复杂度（只说「应受控」不给数字）、依赖注入优于静态单例。

风格与篇幅以此为准，其余四份照此写：

```markdown
# 工程规范

> 适用范围：全部服务，与具体业务领域无关。各服务的专属约定见各自仓库 `docs/` 下的文档。

## 1. 命名

类名用名词，方法名用动词短语。布尔返回的方法以 `is` / `has` / `can` 开头。
缩写除行业通用者外一律展开。同一概念在整个代码库中只用一个词，不得在不同层
使用不同叫法。

## 2. 异常处理

不得吞掉异常。捕获后若不处理，必须原样向上抛出或包装为带上下文的领域异常。
禁止捕获 `Exception` 或 `Throwable` 这类宽泛类型，除非位于线程或任务的最外层
边界，且在那里必须记录完整堆栈。

异常信息面向排障者，不面向终端用户。不得把内部结构、SQL 语句或凭据写进
向外返回的错误消息。

## 3. 日志

...
```

写作要求：中文；每小节 2~4 段；只陈述规则与理由，不出现任何数值阈值、费率、
金额、算法强度参数或业务实体名（订单、结算、租户等）。

- [ ] **Step 2: security-baseline.md**

覆盖：参数化查询是访问数据库的唯一允许方式；身份与权限必须来自认证上下文，不得来自请求参数；密码与令牌禁止使用不可抗碰撞的摘要算法；凭据禁止出现在源码与前端产物中；外部回调必须验签；敏感字段在响应与日志中必须脱敏。

**注意**：「身份与权限必须来自认证上下文」这一条与 T1 的判定有重叠。这是刻意的——它让 T1 在只关联通用规范时也可能被发现（第 2 档），但要指出「这是跨租户越权、对应 INC-2025-07」仍必须读 `tenant-isolation.md` 与 `bug-history.md`。

- [ ] **Step 3: api-design-guide.md**

覆盖：统一响应结构；分页必须有上限；错误码稳定且可枚举；写操作必须支持幂等键；版本演进策略；查询参数必须校验白名单（不列举具体字段）。

- [ ] **Step 4: data-handling-policy.md**

覆盖：数据分级（公开/内部/敏感/机密）；敏感数据出域必须脱敏；批量导出必须限量并留审计；数据保留与删除；跨域数据访问需显式授权。

- [ ] **Step 5: code-review-checklist.md**

一份评审清单，分组为：正确性、安全、性能、可观测性、可维护性。每组 4~6 条，写成疑问句（「这个查询是否绑定了调用方的数据边界？」），不给具体阈值。

- [ ] **Step 6: 自检——不得出现具体数值**

```bash
grep -nE "0\.6%|0\.008|100 分|cost *[≥>=]+ *12|12 万|2MB|top-k" demo-repos/knowledge-shared/*.md
```

预期：无输出。有命中就删掉该数值，改成定性表述。

- [ ] **Step 7: 检查体量**

```bash
wc -c demo-repos/knowledge-shared/*.md
```

预期：每份 1500~2500，合计 8000~12000 字符。

- [ ] **Step 8: 提交**

```bash
git add demo-repos/knowledge-shared
git commit -m "docs(demo): add cross-repository engineering standards for retrieval testing"
```

---

### Task 8: 干扰文档

**Files:**
- Create: `demo-repos/knowledge-noise/frontend-style-guide.md`
- Create: `demo-repos/knowledge-noise/mobile-release-process.md`
- Create: `demo-repos/knowledge-noise/oncall-rotation.md`

**Interfaces:**
- Produces: 3 份与被审代码无关的 Markdown。验收方式是审查 Java 仓库时它们**不出现**在引用列表中。

**内容硬约束：不得包含任何与后端代码审查相关的词汇。** 出现 SQL、注入、租户、越权、幂等、脱敏、审计等词都会让干扰文档变成有效召回，失去证伪能力。

每份 1000~1500 字符。

- [ ] **Step 1: frontend-style-guide.md**

内容：CSS 类命名（BEM）、设计令牌与色板、间距刻度、响应式断点、图标规范、暗色模式。纯样式话题。

- [ ] **Step 2: mobile-release-process.md**

内容：iOS/Android 版本号规则、灰度放量节奏、应用商店审核周期、崩溃率门槛、回滚流程、发版窗口。纯流程话题。

- [ ] **Step 3: oncall-rotation.md**

内容：值班班次划分、轮换周期、交接清单、响应时限分级、升级路径、补休规则。纯排班话题。

- [ ] **Step 4: 自检——不得出现审查相关词汇**

```bash
grep -niE "sql|注入|租户|越权|幂等|脱敏|审计|权限|加密|令牌|结算|订单" demo-repos/knowledge-noise/*.md
```

预期：无输出。有命中就改写该句。

- [ ] **Step 5: 提交**

```bash
git add demo-repos/knowledge-noise
git commit -m "docs(demo): add unrelated documents so retrieval precision is falsifiable"
```

---

### Task 9: 目录更名与素材总览

**Files:**
- Rename: `demo-repos/evaluation/` → `demo-repos/build-tool-fixtures/`
- Create: `demo-repos/README.md`

**Interfaces:**
- Consumes: Task 5~8 的产物路径
- Produces: `demo-repos/README.md`，Task 10 的文档同步引用它

- [ ] **Step 1: 更名**

```bash
git mv demo-repos/evaluation demo-repos/build-tool-fixtures
```

- [ ] **Step 2: 确认无代码引用旧路径**

```bash
grep -rn "demo-repos/evaluation" --include=*.java --include=*.ts --include=*.vue --include=*.yml . | grep -v .worktrees || echo "无代码引用"
```

预期：`无代码引用`。文档中的引用由 Task 10 处理。

- [ ] **Step 3: 写 demo-repos/README.md**

必须包含以下小节：

1. **三个仓库一览** —— 名称、语言、领域、feature 分支名、对照表编号区间（M1~M10 / P1~P15 / T1~T18）
2. **上手三步** —— `bash scripts/init-demo-repos.sh` → 绑定仓库（provider `LOCAL`，填绝对路径）→ 上传知识文档
3. **知识库三层** —— 仓库专属 `<repo>/docs/`（4 份）、通用规范 `knowledge-shared/`（5 份）、干扰文档 `knowledge-noise/`（3 份）；说明每个项目共传 12 份
4. **三档对照实验** —— 不关联 / 只关联通用规范 / 全部关联，及各档的预期发现范围
5. **诚实边界** —— 逐条写入规格第 10 节的 7 条

- [ ] **Step 4: 验证脚本仍全绿**

```bash
bash scripts/verify-demo-repos.sh
echo "EXIT=$?"
```

预期：`EXIT=0`。

- [ ] **Step 5: 暂存并检查没有产生 gitlink**

Task 6 之后 `demo-repos/<repo>/` 内部已有 `.git`。git 可能把它们识别为嵌入式仓库并以 gitlink（模式 `160000`）方式暂存，那会把三个仓库的全部文件从主仓库索引中抹掉。暂存后必须核对。

```bash
git add -A demo-repos
git ls-files -s demo-repos | awk '$1=="160000"'
```

预期：**无输出**。若有输出，立即回退并改用显式路径暂存：

```bash
git reset demo-repos
git add demo-repos/build-tool-fixtures demo-repos/README.md
```

- [ ] **Step 6: 提交**

```bash
git commit -m "docs(demo): rename the build tool fixtures and add a materials overview"
```

---

### Task 10: 同步既有文档

**Files:**
- Modify: `docs/演示素材与缺陷对照表.md`
- Modify: `docs/完整功能测试方案.md`
- Modify: `docs/12_服务器部署与演示手册.md`
- Modify: `docs/功能测试准备清单.md`

**Interfaces:**
- Consumes: `scripts/demo-repos-expected-sha.txt`（Task 6）、`demo-repos/README.md`（Task 9）

- [ ] **Step 1: 对照表——写死真实 SHA**

`docs/演示素材与缺陷对照表.md` 第二节三张提交表，用 `scripts/demo-repos-expected-sha.txt` 里的真实短 SHA 替换现有的 `0ba62e8` / `8d9b93a` / `a8fe7c8` 等旧值。每个仓库现在是 3 个 commit（源码、知识文档、feature），表格结构随之调整。

```bash
cat scripts/demo-repos-expected-sha.txt
```

- [ ] **Step 2: 对照表——更新第七节的注意事项**

现有第七节「克隆到新机器时的注意事项」里那段手工 `git init` 脚本已被 `scripts/init-demo-repos.sh` 取代。整段替换为：

```markdown
### 克隆到新机器时

演示仓库的**文件**在主仓库里，克隆能拿到；它们各自的 `.git` 不会被推送。在克隆出的目录里执行一次重建即可：

```bash
bash scripts/init-demo-repos.sh          # Linux / macOS / Git Bash
pwsh -File scripts/init-demo-repos.ps1   # Windows PowerShell
```

脚本把 author、committer 的姓名、邮箱与时间全部钉死，因此**任意机器重建出的 commit SHA 完全一致**，与本文第二节的表格对得上。加 `--verify` / `-Verify` 可以现场核对。
```

- [ ] **Step 3: 对照表——新增知识库分层一节**

在第四节（缺陷对照表）之后插入新的一节，包含：

- 三层结构说明与各层文件清单
- 判定收紧规则：A 类命中通用规范属正常，**B/C 类必须命中仓库专属文档**才算 RAG 起效
- 三档对照实验表与记录模板：

```markdown
| 档位 | 关联文档 | Finding 数 | 其中 A 类 | B 类 | C 类 | 引用了干扰文档？ |
|---|---|---|---|---|---|---|
| 1 | 无 | | | | | |
| 2 | 仅 knowledge-shared 5 份 | | | | | |
| 3 | 全部 12 份 | | | | | |
```

- [ ] **Step 4: 测试方案——修正 M24 语料路径**

`docs/完整功能测试方案.md` 的 M24 小节，把「素材在 `demo-repos/evaluation/`（java/javascript/python 三套）」改为：

```markdown
`evaluation/` 包：`EvaluationCorpusService`、`EvaluationMetrics`、`EvaluationReport`、`RuntimeComparisonReport`。语料在**主仓库顶层** `evaluation/manifest.json`（见 `EvaluationCorpusServiceTest`）。`demo-repos/build-tool-fixtures/` 是构建工具识别用的样本，与评测无关。
```

- [ ] **Step 5: 测试方案——M6-20 改三档、M23-1 补判定方式**

M6-20 由两档对照改为三档，与对照表新增小节一致。M23-1 的预期列补上：「`knowledge-noise/` 的三份文档不应出现在引用列表中」。

- [ ] **Step 6: 测试方案——标注本轮不覆盖项**

在 M10 与 M9-7 的小节开头加一行说明：

```markdown
> 本轮不覆盖：沙箱工具镜像未构建（`SANDBOX_TOOL_IMAGE` 为空）。隔离策略已实现并有单测覆盖（`ContainerPolicy` / `SandboxCommandCatalog`）。补丁会标记为不可审批并附原因，这是 `ValidatingPatchStepExecutor` 的设计内降级，不是故障。
```

- [ ] **Step 7: 部署手册——更新脚本名与仓库数**

`docs/12_服务器部署与演示手册.md` 第 106 行附近提到 `init-demo-repo.sh` 只初始化 `mall-order-service`。改为 `scripts/init-demo-repos.sh`，说明它一次初始化三个仓库并重建 PR 分支，且加 `--verify` 可核对 SHA。第 299 行的知识库上传步骤补上通用规范与干扰文档（每个项目共 12 份）。

- [ ] **Step 8: 准备清单——更新演示仓库小节**

`docs/功能测试准备清单.md` 第 3.1 节现在描述的是两个 commit 的旧结构，更新为三仓库 + 重建脚本，并指向 `demo-repos/README.md`。

- [ ] **Step 9: 核对 43 条缺陷仍可定位**

本计划不重命名任何类或方法，所以对照表第四节的「位置」列不需要改动。但删除了 `com.example.mallorder` 包（已确认对照表未引用它），且缺陷代码现在位于 feature 分支，需要确认逐条仍能定位。

```bash
bash scripts/init-demo-repos.sh
for r in mall-order-service payment-settlement-service tenant-user-center; do
  git -C demo-repos/$r switch -q "$(git -C demo-repos/$r branch --format='%(refname:short)' | grep -v '^main$')"
done

grep -c "^| M[0-9]" docs/演示素材与缺陷对照表.md   # 期望 10
grep -c "^| P[0-9]" docs/演示素材与缺陷对照表.md   # 期望 15
grep -c "^| T[0-9]" docs/演示素材与缺陷对照表.md   # 期望 18

grep -l "batchShip\|recalculatePaidAmount\|searchActivityOrders" demo-repos/mall-order-service/src/main/java/com/example/mall/service/PromotionShipService.java
grep -l "InstantSettlementService\|forceRefund" -r demo-repos/payment-settlement-service/src
grep -l "search_users\|export_users\|reset_password" demo-repos/tenant-user-center/src/app/ops_console.py
grep -l "OPS_API_KEY\|renderSearchResults\|applyUserPreferences" demo-repos/tenant-user-center/web/ops-console.js

for r in mall-order-service payment-settlement-service tenant-user-center; do
  git -C demo-repos/$r switch -q main
done
```

预期：三个计数分别为 10 / 15 / 18，四条 `grep -l` 都打印出文件路径。最后必须切回 `main`，否则主仓库工作区会多出未跟踪的 feature 文件。

- [ ] **Step 10: 全文检查旧路径残留**

```bash
grep -rn "init-demo-repo\.sh\|demo-repos/evaluation" docs/ | grep -v .worktrees || echo "无残留"
```

预期：`无残留`。

- [ ] **Step 11: 提交**

```bash
git add docs
git commit -m "docs: align the test plan and defect map with the rebuilt demo materials"
```

---

## 完成标准

全部任务结束后，以下命令应当全部通过：

```bash
rm -rf demo-repos/*/.git
bash scripts/init-demo-repos.sh --verify   # 6 行 ok，EXIT=0
bash scripts/verify-demo-repos.sh          # 全部 ok，EXIT=0
git -C demo-repos/mall-order-service log --oneline --all        # 4 个 commit（main 2 + feature 1，共享前 2 个）
git -C demo-repos/mall-order-service diff main..feature/promotion-batch-ship --stat   # 2 files changed
```

服务器完整版部署后，核心链路应端到端跑通：登录 → 建项目 → 绑仓库（provider `LOCAL`）→ 导入 PR（base `main`，head 为 feature 分支 SHA）→ 上传 12 份知识文档至 `INDEXED` → 创建审查 → 对照 43 条缺陷核验 → Finding → Patch → 报告 → 审批。
