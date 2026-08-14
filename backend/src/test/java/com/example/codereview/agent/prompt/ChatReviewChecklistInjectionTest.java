package com.example.codereview.agent.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.review.DiffSplitter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * r8-R2 类型化清单注入的行为测试(字节级钉死在 {@link ChatReviewPromptGoldenTest}):
 * 扩展名映射(研究稿 §3.2)、固定顺序并集(java &gt; frontend-ts)、无命中回落 generic
 * (含 sql/config——弹药为零不建清单的机制性裁定)、与上游 DiffSplitter 截断的相互作用
 * (清单为任务层静态短文本,永不被截断,不占 diff/context 预算,§3.4)。
 */
class ChatReviewChecklistInjectionTest {

    private static final String JAVA_HEADER = "审查重点（Java）：";
    private static final String TS_HEADER = "审查重点（TS/JS）：";
    private static final String GENERIC_HEADER = "审查重点：\n1. 空指针风险";

    private final AgentPromptAssembler assembler = new AgentPromptAssembler(
            new PromptTemplateRegistry()
    );

    @Test
    void javaOnlyDiffInjectsJavaChecklistOnceEvenWithMultipleJavaFiles() {
        String diff = fileDiff("src/main/java/com/acme/A.java", "+int a;")
                + fileDiff("src/main/java/com/acme/B.java", "+int b;");

        AgentPromptAssembler.ChatReviewPrompt prompt = assembler.assembleChatReview(diff, "ctx");

        assertThat(prompt.checklistTemplateVersions()).containsExactly("checklist-java-v1");
        assertThat(prompt.userMessage()).contains(JAVA_HEADER).doesNotContain(TS_HEADER).doesNotContain(GENERIC_HEADER);
    }

    @Test
    void everyFrontendTsExtensionSelectsTsChecklist() {
        for (String extension : List.of("vue", "ts", "tsx", "js", "jsx", "mjs")) {
            AgentPromptAssembler.ChatReviewPrompt prompt = assembler.assembleChatReview(
                    fileDiff("web/src/app." + extension, "+const x = 1;"), "ctx");

            assertThat(prompt.checklistTemplateVersions())
                    .as("extension .%s", extension)
                    .containsExactly("checklist-ts-v1");
        }
    }

    @Test
    void mixedDiffInjectsOrderedUnionRegardlessOfFileOrder() {
        // ts 文件先出现,并集顺序仍固定 java > frontend-ts(确定性与 diff 内文件顺序无关)。
        String diff = fileDiff("web/src/app.ts", "+const x = 1;")
                + fileDiff("api/src/Svc.java", "+int y;");

        AgentPromptAssembler.ChatReviewPrompt prompt = assembler.assembleChatReview(diff, "ctx");

        assertThat(prompt.checklistTemplateVersions())
                .containsExactly("checklist-java-v1", "checklist-ts-v1");
        assertThat(prompt.userMessage().indexOf(JAVA_HEADER))
                .isLessThan(prompt.userMessage().indexOf(TS_HEADER));
    }

    @Test
    void extensionMatchingIsCaseInsensitive() {
        AgentPromptAssembler.ChatReviewPrompt prompt = assembler.assembleChatReview(
                fileDiff("src/Legacy.JAVA", "+int a;"), "ctx");

        assertThat(prompt.checklistTemplateVersions()).containsExactly("checklist-java-v1");
    }

    @Test
    void unmatchedExtensionsFallBackToGeneric() {
        // .py/.md 未建清单;.sql/.yml 是研究稿 §1.3 裁定弹药为零不建文件的两类,必须回落而非留空。
        String diff = fileDiff("scripts/tool.py", "+print(1)")
                + fileDiff("docs/README.md", "+# doc")
                + fileDiff("db/migration/V9__x.sql", "+select 1;")
                + fileDiff("config/app.yml", "+key: value");

        AgentPromptAssembler.ChatReviewPrompt prompt = assembler.assembleChatReview(diff, "ctx");

        assertThat(prompt.checklistTemplateVersions()).containsExactly("checklist-generic-v1");
        assertThat(prompt.userMessage()).contains(GENERIC_HEADER)
                .doesNotContain(JAVA_HEADER)
                .doesNotContain(TS_HEADER);
    }

    @Test
    void headerlessNullAndBlankDiffsFallBackToGenericWithoutEmptySlot() {
        for (String diff : new String[] {null, "", "+raw change without any file header"}) {
            AgentPromptAssembler.ChatReviewPrompt prompt = assembler.assembleChatReview(diff, "ctx");

            assertThat(prompt.checklistTemplateVersions())
                    .as("diff=%s", diff)
                    .containsExactly("checklist-generic-v1");
            assertThat(prompt.userMessage()).as("diff=%s", diff).contains(GENERIC_HEADER);
        }
    }

    @Test
    void truncatedJavaChunkStillGetsFullJavaChecklist() {
        // 清单槽与超长截断的相互作用(上游截断,§3.4):DiffSplitter 对超预算文件原地截断后,
        // 分片仍保留文件头 → 类型判定不受影响;清单是模板内静态文本,不参与 diff/context 预算,
        // 注入后必须完整(以首尾条目在场为证),截断标记与清单并存。
        String diff = fileDiff("src/main/java/com/acme/Big.java", "+x".repeat(600));
        DiffSplitter.ChunkPlan plan = DiffSplitter.plan(diff, 300, 40);
        assertThat(plan.chunks()).hasSize(1);
        String chunk = plan.chunks().get(0);
        assertThat(chunk).contains("diff 已截断");

        AgentPromptAssembler.ChatReviewPrompt prompt = assembler.assembleChatReview(chunk, "ctx");

        assertThat(prompt.checklistTemplateVersions()).containsExactly("checklist-java-v1");
        assertThat(prompt.userMessage())
                .contains(JAVA_HEADER)
                .contains("1. 金额运算纪律")
                .contains("10. \"行为零变化\"搬移的分支丢失")
                .contains("diff 已截断");
    }

    @Test
    void headerlessTruncatedChunkFromSplitterFallsBackToGeneric() {
        // 无文件头文本经 DiffSplitter 成为 path=null 的单段分片,超预算被原地截断;
        // parsePath 为 null → 无类型命中 → generic 回落(研究稿 §3.2 最后一行)。
        String raw = "+no header line ".repeat(60);
        DiffSplitter.ChunkPlan plan = DiffSplitter.plan(raw, 200, 40);
        assertThat(plan.chunks()).hasSize(1);
        String chunk = plan.chunks().get(0);
        assertThat(chunk).contains("已截断");

        AgentPromptAssembler.ChatReviewPrompt prompt = assembler.assembleChatReview(chunk, "ctx");

        assertThat(prompt.checklistTemplateVersions()).containsExactly("checklist-generic-v1");
        assertThat(prompt.userMessage()).contains(GENERIC_HEADER);
    }

    private static String fileDiff(String path, String addedLine) {
        return "diff --git a/" + path + " b/" + path + "\n"
                + "--- a/" + path + "\n"
                + "+++ b/" + path + "\n"
                + "@@ -1,1 +1,2 @@\n"
                + addedLine + "\n";
    }
}
