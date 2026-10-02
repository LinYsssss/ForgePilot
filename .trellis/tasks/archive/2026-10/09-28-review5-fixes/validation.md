# review-5 验证记录（2026-09-30）

## 状态

本地实现及自动化验证完成，尚未提交、推送或部署，未触发真实模型复测。基于 `9af6857` 工作树；任务保持 in_progress，等待提交确认。不声明真实浏览器或新模型分类质量已验收。

## 后端

执行一次：

```bash
docker run --rm --network host -v /root/ForgePilot/backend:/workspace \
  -v /root/.m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock \
  -w /workspace eclipse-temurin:21-jdk ./mvnw -B -ntp verify
```

真实输出摘要：

```text
Tests run: 422, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 15:26 min
```

本次涉及的四个现有类均通过：ReviewOutputValidatorTest 26、ReviewPipelineIntegrationTest 10、ReviewDecisionTest 16、FindingLifecycleTest 12。只新增一个集中详情测试方法，其余扩展现有方法；没有新增或改名测试类。未跑第二次全量或定向重跑。

日志：`/tmp/claude-0/-root/f00941bb-5869-44ae-84e2-947b854381ac/tasks/bq3v6i48y.output`（临时日志，不保证永久保留）。

## 前端

依次执行 `npm run lint`、`npm run typecheck`、`npm test -- --run`、`npm run build`，整个命令退出 0：

```text
Frontend foundation policy checks passed.
Test Files 18 passed (18)
Tests 63 passed (63)
90 modules transformed.
built in 11.14s
```

journey.spec.ts 在既有开发者流程中验证同一 IN_PROGRESS Finding：他人认领时按钮隐藏，认领清空或为当前人时显示。

非失败提示：模拟 DOM 不实现 Window.scrollTo/Canvas.getContext；Vite 提示插件钩子耗时。未修改依赖或测试环境来掩盖提示；mock DOM 通过不等于完成真实浏览器的布局、滚动或手工验收。

日志：`/tmp/claude-0/-root/f00941bb-5869-44ae-84e2-947b854381ac/tasks/bxyzt1mab.output`。

## 不可变资产与静态检查

- `formal_evaluation.py verify-freeze`：通过，freezeHash `55fc3176b6c843d214aa405781c1fe404ee7663c3e177f8a3cd67d9b22021e5d`。
- `postfreeze_provider_correction.py verify`：通过，correctionHash `8c2401958679866753b87926d8c1b28939a2390189c612b3421409139734100c`；model/temperature/prompt/scorer/corpus/split 均未变。
- `git diff --exit-code HEAD -- evaluation backend/src/main/resources/db/migration docs/thesis/*.csv docs/thesis/verify-*.py`：通过，无改动。
- `git diff --check` 与本次变动文本 U+FFFD 扫描：通过。
- Trellis `task.py validate`：implement.jsonl 5 项、check.jsonl 4 项，全部路径有效。
- 13 个变更源码/测试文件按路径排序后，将 `path + NUL + 文件内容 + NUL` 连接所得 SHA-256：`3ded6278b6784b5180dce9baa6c66c212acaa0854de34f5418c52c904feb926b`。上述自动化验证对应此源码快照；后续全仓审计仅改源码注释、规范和文档，去注释后的可执行 token 一致性及保护区校验见 `audit.md`。

## 复核结论

- 类型归一化先拒绝外来 AC，不靠删引用绕过合法性校验；保留父 Review 上下文。
- 展示层宽松比对只在 detail 使用，不进入 lineage/suppression；跨需求同 AC、未知行号/AC 不互相遮蔽。
- 无认领例外仍经过项目角色检查；实际 actor 写入既有事件，不自动认领。
- correctedLines 只含 Finding 纠行事件，文案说明分批候选；保留旧 warning/CSV 统计口径并在论文补充区别。
- 未触及部署配置、密钥、生产数据、历史迁移和正式评测。

## 未执行 / 后续确认门

- 真实浏览器手工验收与线上 API 抽查：未执行。
- 提交、推送、部署：未执行，需确认提交分组及外部操作。
- 新轮次 PR 空提交/真实模型 C 轮：未执行，需单独批准目标清单与消耗。
- 因而尚不能给出 review-5 在真实模型上的分类比例、召回或稳定性改善结论。
