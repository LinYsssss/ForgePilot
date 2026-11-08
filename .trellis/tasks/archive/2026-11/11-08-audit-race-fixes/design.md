# 设计

## D1 质量检查写回：锁内比较内容

**为什么用需求行锁。** `RequirementService.editDraft` 与 `publishRevision` 都先执行 `requirements.lockActiveByProjectIdAndId`（`SELECT ... FOR UPDATE`）。写回也拿同一把锁，就能和编辑串行化：

1. `check()` 照旧在无事务的情况下读取修订与 AC，额外记下一份内容快照 `Content(title, background, description, List<(acKey, text)>)`，按 `sort_order` 排序。
2. 外部调用照旧在锁外完成。
3. `store()` 改为 `TransactionTemplate` 短事务：
   - `lockActiveByProjectIdAndId(projectId, requirementId)`；需求已被软删时返回 404。
   - 锁内用 JDBC 重新读取该修订的正文与 AC，组装出同样的 `Content`。READ COMMITTED 下，每条语句都能看到等锁期间已提交的编辑。
   - 不相等 → `ApiException.conflict("需求内容在检查期间已变化，请重新运行质量检查。")`，不写库。
   - 相等 → 执行原来那条 UPDATE。

**为什么不只靠一条带条件的 UPDATE。** 若编辑只改了 AC，而质量列原本就是 NULL，Hibernate 不会更新 `requirement_revision` 行，单条语句就拿不到需要等待的行锁，会读到编辑提交前的 AC。需求行锁对所有编辑都成立。

**为什么比较完整内容而不比较修订 ID 或时间戳。** DRAFT 原地编辑不换修订 ID；修订上也没有 updated_at 列。比较内容只是对一行加几条 AC 做比对，成本可以忽略，而且不需要迁移。

**兼容性。** 已发布修订不可变，内容总是相等，行为不变。409 走既有的统一错误体，前端 `apiErrorMessage` 会直接显示这条消息。类注释中「已知竞态」一段改为说明这个比较。

## D2 前端质量请求序号

照搬同一文件里 `guidanceRequest` / `invalidateGuidance` 的模式：

- `let qualityRequest = 0;` 以及 `invalidateQuality()`：递增序号，清空 report、pending、error。
- `runQualityCheck`：发请求前取 `request = ++qualityRequest`；成功、失败和 finally 都只在 `request === qualityRequest` 时写状态。
- 原本只清 `qualityReport` 的三处（`applyDetail` 修订变化、`load()`、`run(clearAdvice)`）改为调用 `invalidateQuality()`。`applyDetail` 中「同一草稿内容变化」的判断与实现建议共用一个条件。`onBeforeUnmount` 同时作废两者。

不新增 composable：本页只有这两处请求需要序号，抽象出来不划算。

## D3 PR 快照来源身份

`apply()` 拿到加锁的 `repository` 后加一个判断：

```java
if (repository.getProvider() != detached.getProvider()
        || !repository.getInstanceIdentity().equals(detached.getInstanceIdentity())
        || !repository.getExternalId().equals(detached.getExternalId())) {
    throw unauthenticated();
}
```

`detached` 就是 `authenticate()` 读出、控制器随后用来 `fetch` 的那个对象，所以它的三元组就是拉取所用的身份。返回 401 的理由：同一份投递晚到几毫秒，就会在 `authenticate` 阶段因查不到仓库而得到 401；两者应当不可区分。凭据和 api_base 不在比较之列。`ScmIdentityFreezeConcurrencyTest` 类注释里「未覆盖、仍开放」那段已经过时，改写为说明加锁 + 来源比较。

## 测试（只写承重的那几条）

| 测试 | 锁住的不变量 |
|---|---|
| `RequirementQualityTest`：调用在途时编辑正文 → 409，无存储 | 晚到结果不能落在已变化的内容上 |
| `RequirementQualityTest`：调用在途时只改 AC → 409 | 比较范围包含 AC，而不只是修订行 |
| `ScmIdentityFreezeConcurrencyTest`：身份变更后旧快照 → 401，PR 数 0 | 快照来源身份必须等于当前身份 |
| `requirement.spec.ts`：质量请求在途时保存草稿，旧响应不显示 | 前端作废在途质量响应 |

「内容不变时正常写入」已经由现有 `theResultLandsOnTheCurrentRevisionAndNotOnAnEarlierOne` 等测试覆盖，不再重复。

## 回滚

三个修复分三个提交，互不依赖，可以单独 revert。没有迁移，也没有数据变更。
