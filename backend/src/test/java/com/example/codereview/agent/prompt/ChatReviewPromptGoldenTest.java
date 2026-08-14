package com.example.codereview.agent.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * r8-R2 golden 快照测试(chat 审查路径):任务层 v2 全文钉死。goldenV2* 生成器逐字复刻
 * chat-review-task-v2 与三份清单模板(checklist-java/ts/generic-v1)的期望字节,对代表性
 * 输入逐字节比对——模板文件是行为资产,任何字节漂移在此先炸。
 *
 * <p>legacy* 生成器保存重构前 OpenAiCompatibleReviewClient.systemPrompt()/buildPrompt()
 * 的旧代码原文(r8-R1 的字节等价基准):系统层与 generic 回落路径(无 java/frontend-ts
 * 类型命中)必须仍与 legacy 逐字节相同——R1 的字节等价保证在该路径延续,v2 相对 v1 的
 * 内容性变更被隔离在类型命中的清单块内(评测门禁按规则三走服务器全量对比)。
 */
class ChatReviewPromptGoldenTest {

    private final AgentPromptAssembler assembler = new AgentPromptAssembler(
            new PromptTemplateRegistry()
    );

    @Test
    void systemMessageStaysByteIdenticalToLegacySystemPrompt() {
        AgentPromptAssembler.ChatReviewPrompt prompt = assembler.assembleChatReview("diff", "ctx");

        assertThat(prompt.systemMessage()).isEqualTo(legacySystemPrompt());
    }

    @Test
    void genericFallbackUserMessageStaysByteIdenticalToLegacy() {
        // 旧代码分支:ragContext 为 null/空白 → 兜底文案;diffText 为 null → 空串。
        // 这些输入无文件头/无类型命中 → generic 回落,产出必须仍与旧内联文本逐字节相同。
        assertThat(assembler.assembleChatReview(null, null).userMessage())
                .isEqualTo(legacyBuildPrompt(null, null));
        assertThat(assembler.assembleChatReview("", "").userMessage())
                .isEqualTo(legacyBuildPrompt("", ""));
        assertThat(assembler.assembleChatReview("+x", "   ").userMessage())
                .isEqualTo(legacyBuildPrompt("+x", "   "));
        assertThat(assembler.assembleChatReview("+x", "   ").userMessage())
                .contains("未检索到项目上下文。");
    }

    @Test
    void goldenGeneratorsAgreeOnGenericIdentity() {
        // 自证:v2 模板 + generic 清单 == v1 全文(六大类块之外 v2 与 v1 一字不差的机器验收)。
        assertThat(goldenV2BuildPrompt(GOLDEN_GENERIC_CHECKLIST, "+x", "ctx"))
                .isEqualTo(legacyBuildPrompt("+x", "ctx"));
        assertThat(goldenV2BuildPrompt(GOLDEN_GENERIC_CHECKLIST, null, null))
                .isEqualTo(legacyBuildPrompt(null, null));
    }

    @Test
    void javaDiffPinsTaskV2WithJavaChecklist() {
        String rag = "[citation:arch.md#chunk-1]\n本项目的服务层禁止自调用事务方法。";
        String diff = "diff --git a/OrderService.java b/OrderService.java\n"
                + "@@ -10,6 +10,7 @@\n"
                + "+    orderMapper.update(order);";

        AgentPromptAssembler.ChatReviewPrompt prompt = assembler.assembleChatReview(diff, rag);

        assertThat(prompt.userMessage()).isEqualTo(goldenV2BuildPrompt(GOLDEN_JAVA_CHECKLIST, diff, rag));
    }

    @Test
    void tsDiffPinsTaskV2WithTsChecklist() {
        String rag = "项目技术栈:Node 服务端 TypeScript。";
        String diff = "diff --git a/src/user.ts b/src/user.ts\n"
                + "+++ b/src/user.ts\n"
                + "+export const name = user?.profile.name;";

        AgentPromptAssembler.ChatReviewPrompt prompt = assembler.assembleChatReview(diff, rag);

        assertThat(prompt.userMessage()).isEqualTo(goldenV2BuildPrompt(GOLDEN_TS_CHECKLIST, diff, rag));
    }

    @Test
    void mixedDiffPinsOrderedUnionJavaBeforeTs() {
        // ts 文件在 diff 中先出现,并集顺序仍固定 java > frontend-ts(确定性,与出现顺序无关)。
        String rag = "ctx";
        String diff = "diff --git a/web/src/files.ts b/web/src/files.ts\n"
                + "+++ b/web/src/files.ts\n"
                + "+import { join } from 'path';\n"
                + "diff --git a/api/src/InvoiceController.java b/api/src/InvoiceController.java\n"
                + "+++ b/api/src/InvoiceController.java\n"
                + "+    return repository.findById(id);";

        AgentPromptAssembler.ChatReviewPrompt prompt = assembler.assembleChatReview(diff, rag);

        assertThat(prompt.userMessage()).isEqualTo(goldenV2BuildPrompt(
                GOLDEN_JAVA_CHECKLIST + "\n" + GOLDEN_TS_CHECKLIST, diff, rag));
    }

    @Test
    void userMessageMatchesPerShardChecklistForShardStyleDiff() {
        // 分片审查:ReviewProcessor/DiffSplitter 按分片逐次调用 review(),清单按各分片自身的
        // 文件类型逐片选取(方案 A 的分片粒度),系统/项目层内容跨分片相同。
        String context = "项目技术栈:Spring Boot 3 + MyBatis。";
        String javaShard = "diff --git a/A.java b/A.java\n+int a = 1;";
        String tsShard = "diff --git a/b.ts b/b.ts\n+const b = 2;";

        AgentPromptAssembler.ChatReviewPrompt first = assembler.assembleChatReview(javaShard, context);
        AgentPromptAssembler.ChatReviewPrompt second = assembler.assembleChatReview(tsShard, context);

        assertThat(first.userMessage()).isEqualTo(goldenV2BuildPrompt(GOLDEN_JAVA_CHECKLIST, javaShard, context));
        assertThat(second.userMessage()).isEqualTo(goldenV2BuildPrompt(GOLDEN_TS_CHECKLIST, tsShard, context));
        assertThat(first.systemMessage()).isEqualTo(second.systemMessage());
    }

    @Test
    void formatSpecifiersInsideInjectedValuesAreNeverReinterpreted() {
        // 旧 .formatted 只解析模板字面量,不复扫已注入值;分层组装(项目层先渲染再作为任务层
        // 槽值,清单块同为槽值)必须保持同一性质,否则含 % 的 diff/知识内容会炸格式化或被二次替换。
        String rag = "SQL 拼接如 \"select * from t where name like '%s'\" 属高危。";
        String headerlessDiff = "+String url = base + \"?rate=100%\";\n+log.info(\"%d records\");";

        AgentPromptAssembler.ChatReviewPrompt generic = assembler.assembleChatReview(headerlessDiff, rag);
        assertThat(generic.userMessage()).isEqualTo(legacyBuildPrompt(headerlessDiff, rag));
        assertThat(generic.userMessage()).contains("like '%s'").contains("rate=100%").contains("%d records");

        String javaDiff = "diff --git a/A.java b/A.java\n+String q = \"like '%s' %% %d\";";
        AgentPromptAssembler.ChatReviewPrompt typed = assembler.assembleChatReview(javaDiff, rag);
        assertThat(typed.userMessage()).isEqualTo(goldenV2BuildPrompt(GOLDEN_JAVA_CHECKLIST, javaDiff, rag));
        assertThat(typed.userMessage()).contains("like '%s' %% %d");
    }

    @Test
    void templateVersionsAreExposedForCallSiteLogging() {
        // chat 路径版本记录先走日志(ai_call_log 列扩展是 r8 遗留项),版本字段是日志契约;
        // r8-R2 起附注入的清单模板版本(promptHash↔版本↔评测数字互查的规则三锚点)。
        AgentPromptAssembler.ChatReviewPrompt javaPrompt =
                assembler.assembleChatReview("diff --git a/A.java b/A.java\n+1", "ctx");
        assertThat(javaPrompt.systemTemplateVersion()).isEqualTo("chat-review-system-v1");
        assertThat(javaPrompt.projectTemplateVersion()).isEqualTo("chat-review-project-v1");
        assertThat(javaPrompt.taskTemplateVersion()).isEqualTo("chat-review-task-v2");
        assertThat(javaPrompt.checklistTemplateVersions()).containsExactly("checklist-java-v1");

        AgentPromptAssembler.ChatReviewPrompt genericPrompt = assembler.assembleChatReview("diff", "ctx");
        assertThat(genericPrompt.checklistTemplateVersions()).containsExactly("checklist-generic-v1");
    }

    /** checklist-java-v1 期望字节(研究稿 §2.1 十条,末尾换行是拼接结构字节)。 */
    private static final String GOLDEN_JAVA_CHECKLIST = """
            审查重点（Java）：
            1. 金额运算纪律:金额是否出现 double 中间值、`(long)` 强转截断、`Math.round` 四舍五入;元转分是否用 `BigDecimal.movePointRight(2).longValueExact()` 精确转换
            2. 业务参数硬编码:费率/额度/开关等业务数值是否写死常量,而项目知识文档要求从配置表读取
            3. 新增第二路径的校验完整性:新增批量/即时/极速等旁路业务路径,是否逐项复刻既有单笔路径的全部校验与状态守卫(支付状态、最小净额、币种、`markProcessing` 类状态机唯一拦截点)
            4. 条件更新的字段完整性:UPDATE 改造(如加并发条件)后 SET 字段清单是否与被替换实现一致,有无静默丢字段
            5. 事务边界:`@Transactional` 方法内是否有 `this.` 自调用;新增写路径方法是否缺注解且不经代理调用带注解方法
            6. 资源关闭:JDBC/文件流/HTTP client 等 Closeable 是否 try-with-resources;正常返回、早退 return、异常三类路径是否全部关闭
            7. 空指针三形态:`Optional.get()` 无 isPresent/orElse*;`Map.get()` 返回值直接链式解引用;判空与解引用次序反转(先用后判)
            8. 共享契约第二实现漂移:同一编码/解析契约的内联复制体是否与共享实现同步升级;新增解析/渲染路径是否绕过 javadoc 标明"所有路径必须经过"的共享防御
            9. 启动期环境假设:`@Service`/`@Component` 构造器或 `@PostConstruct` 是否对绝对路径/外部资源做立即 IO(如 `Files.createDirectories`),失败即上下文起不来
            10. "行为零变化"搬移的分支丢失:声称 move-only/纯重构的 diff 中是否有守卫分支被静默删除——孤儿常量、声明未置位字段、javadoc 承诺与实现矛盾为可指认线索
            """;

    /** checklist-ts-v1 期望字节(研究稿 §2.2 三条)。 */
    private static final String GOLDEN_TS_CHECKLIST = """
            审查重点（TS/JS）：
            1. 新增资源入口的属主校验:新增"按 id 操作资源"的 endpoint/handler(尤其删除/修改等破坏性操作)是否调用与同文件既有入口一致的属主/权限断言(`assertOwnership` 形态)
            2. 路径拼接:用户可控路径片段与根目录 `join` 后是否缺规范化与前缀校验(path traversal)
            3. 可空解引用:可选参数(`user?: {...}`)与可空字段是否未经判定直接 `.prop` 链式访问
            """;

    /** checklist-generic-v1 期望字节(v1 六大类原文迁入)。 */
    private static final String GOLDEN_GENERIC_CHECKLIST = """
            审查重点：
            1. 空指针风险
            2. SQL 注入风险
            3. 权限校验缺失
            4. 事务一致性问题
            5. 性能问题
            6. 业务规则破坏
            """;

    /** 旧 OpenAiCompatibleReviewClient.systemPrompt() 原文(golden 生成器,保存旧代码形状)。 */
    private static String legacySystemPrompt() {
        return """
                你是资深 Java 代码审查专家。
                你必须基于 Git Diff、静态分析结果和 RAG 项目上下文进行审查。
                你不能编造不存在的文件、行号或证据。
                你只能输出 JSON，不要输出 Markdown。
                """;
    }

    /** chat-review-task-v2 全文生成器(清单块为槽值,其余正文与 v1 一字不差)。 */
    private static String goldenV2BuildPrompt(String checklistBlock, String diffText, String ragContext) {
        return """
                请审查下面的 Git Diff。

                %s
                输出 JSON Schema：
                {
                  "summary": "string",
                  "overallRisk": "HIGH|MEDIUM|LOW|NONE",
                  "issues": [
                    {
                      "severity": "HIGH|MEDIUM|LOW",
                      "category": "NULL_POINTER|SQL_INJECTION|AUTH_RISK|TRANSACTION_RISK|PERFORMANCE_RISK|BUSINESS_RULE_RISK|UNKNOWN",
                      "filePath": "string",
                      "lineStart": 1,
                      "lineEnd": 1,
                      "title": "string",
                      "description": "string",
                      "impact": "string",
                      "evidenceSources": [{"sourceName":"string","quote":"string"}],
                      "suggestion": "string",
                      "confidence": 0.0
                    }
                  ]
                }

                RAG 项目上下文：
                %s

                Git Diff：
                %s
                """.formatted(
                checklistBlock,
                ragContext == null || ragContext.isBlank() ? "未检索到项目上下文。" : ragContext,
                diffText == null ? "" : diffText
        );
    }

    /** 旧 OpenAiCompatibleReviewClient.buildPrompt() 原文(golden 生成器,保存旧代码形状)。 */
    private static String legacyBuildPrompt(String diffText, String ragContext) {
        return """
                请审查下面的 Git Diff。

                审查重点：
                1. 空指针风险
                2. SQL 注入风险
                3. 权限校验缺失
                4. 事务一致性问题
                5. 性能问题
                6. 业务规则破坏

                输出 JSON Schema：
                {
                  "summary": "string",
                  "overallRisk": "HIGH|MEDIUM|LOW|NONE",
                  "issues": [
                    {
                      "severity": "HIGH|MEDIUM|LOW",
                      "category": "NULL_POINTER|SQL_INJECTION|AUTH_RISK|TRANSACTION_RISK|PERFORMANCE_RISK|BUSINESS_RULE_RISK|UNKNOWN",
                      "filePath": "string",
                      "lineStart": 1,
                      "lineEnd": 1,
                      "title": "string",
                      "description": "string",
                      "impact": "string",
                      "evidenceSources": [{"sourceName":"string","quote":"string"}],
                      "suggestion": "string",
                      "confidence": 0.0
                    }
                  ]
                }

                RAG 项目上下文：
                %s

                Git Diff：
                %s
                """.formatted(
                ragContext == null || ragContext.isBlank() ? "未检索到项目上下文。" : ragContext,
                diffText == null ? "" : diffText
        );
    }
}
