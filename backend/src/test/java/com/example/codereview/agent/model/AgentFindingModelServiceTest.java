package com.example.codereview.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.example.codereview.finding.FindingSeverity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AgentFindingModelServiceTest {

    private final AgentModelCallAuditService audit = mock(AgentModelCallAuditService.class);
    private final AgentModelClient client = mock(AgentModelClient.class);
    private final AgentFindingModelService service = new AgentFindingModelService(
            new ObjectMapper(), audit, 16_384
    );
    private final PromptEnvelope prompt = new PromptEnvelope(
            "policy", "findings", "diff", "", "", "knowledge", "{}",
            "review-v1", null, "finding-candidates-v1", List.of(), List.of("rule#chunk-1")
    );

    @Test
    void acceptsSchemaValidFindingWithSuppliedCitation() {
        when(audit.save(any())).thenAnswer(call -> call.getArgument(0));
        when(client.generate(prompt)).thenReturn(response("""
                {"findings":[{"severity":"HIGH","category":"security","title":"SQL leak",
                "description":"Connection is not closed","filePath":"src/Main.java",
                "lineStart":10,"lineEnd":10,"symbol":"run","ruleId":"JAVA-1",
                "citationIds":["rule#chunk-1"]}]}
                """));

        AgentFindingModelService.Result result = service.generate(
                1L, client, prompt, Set.of("rule#chunk-1")
        );

        assertThat(result.response().findings()).singleElement()
                .extracting(FindingModelResponse.ModelFinding::title)
                .isEqualTo("SQL leak");
    }

    // MiMo 等真实模型常以 ```json 围栏包裹输出:剥离实现与 ModelOutputValidator
    // 同源(ModelJsonOutputs),两条解析路径不得再各自为防(run15 加固)。
    @Test
    void unwrapsMarkdownFencedOutputBeforeParsing() {
        when(audit.save(any())).thenAnswer(call -> call.getArgument(0));
        when(client.generate(prompt)).thenReturn(response("""
                ```json
                {"findings":[{"severity":"HIGH","category":"security","title":"SQL leak",
                "description":"Connection is not closed","filePath":"src/Main.java",
                "lineStart":10,"lineEnd":10,"symbol":"run","ruleId":"JAVA-1",
                "citationIds":["rule#chunk-1"]}]}
                ```
                """));

        AgentFindingModelService.Result result = service.generate(
                1L, client, prompt, Set.of("rule#chunk-1")
        );

        assertThat(result.response().findings()).singleElement()
                .extracting(FindingModelResponse.ModelFinding::title)
                .isEqualTo("SQL leak");
    }

    @Test
    void acceptsLowercaseSeverityFromRealModels() {
        when(audit.save(any())).thenAnswer(call -> call.getArgument(0));
        when(client.generate(prompt)).thenReturn(response("""
                {"findings":[{"severity":"high","category":"security","title":"SQL leak",
                "description":"Connection is not closed","filePath":"src/Main.java",
                "lineStart":10,"lineEnd":10,"symbol":"run","ruleId":"JAVA-1",
                "citationIds":["rule#chunk-1"]}]}
                """));

        AgentFindingModelService.Result result = service.generate(
                1L, client, prompt, Set.of("rule#chunk-1")
        );

        assertThat(result.response().findings().get(0).severity()).isEqualTo(FindingSeverity.HIGH);
    }

    // 审计表按设计不存原始载荷,错误信息是唯一现场:抛错与审计行都必须带解析器
    // 定界原因,不许再出现 run15 那种无从定位的盲错。
    @Test
    void parseFailureCarriesParserDetailInErrorAndAudit() {
        List<AgentModelCall> saved = new ArrayList<>();
        when(audit.save(any())).thenAnswer(call -> {
            saved.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(client.generate(prompt)).thenReturn(response(
                "{\"findings\":[{\"surprise\":true}]}"
        ));

        assertThatThrownBy(() -> service.generate(1L, client, prompt, Set.of("rule#chunk-1")))
                .isInstanceOf(AgentStepExecutionException.class)
                .hasMessageContaining("not schema-valid JSON")
                .hasMessageContaining("surprise");
        assertThat(saved.get(saved.size() - 1).getFailureReason())
                .contains("invalid finding model JSON")
                .contains("surprise");
    }

    // 逐条裁剪(镜像 ReviewPlanValidator.clampOverBudget):个别残缺/越权条目
    // 丢弃留痕,不拖累同批合法发现;WARN 含序号与原因。
    @Test
    void dropsOnlyInvalidFindingsAndWarnsInsteadOfFailingWholeStep() {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AgentFindingModelService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(audit.save(any())).thenAnswer(call -> call.getArgument(0));
            when(client.generate(prompt)).thenReturn(response("""
                    {"findings":[
                    {"severity":"HIGH","category":"security","description":"Missing title",
                    "citationIds":["rule#chunk-1"]},
                    {"severity":"HIGH","category":"security","title":"Fabricated","description":"D",
                    "citationIds":["fabricated"]},
                    {"severity":"LOW","category":"style","title":"Kept","description":"D",
                    "filePath":"src/Main.java","lineStart":2,"lineEnd":2,"symbol":"run","ruleId":"R",
                    "citationIds":["rule#chunk-1"]}]}
                    """));

            AgentFindingModelService.Result result = service.generate(
                    1L, client, prompt, Set.of("rule#chunk-1")
            );

            assertThat(result.response().findings()).singleElement()
                    .extracting(FindingModelResponse.ModelFinding::title)
                    .isEqualTo("Kept");
            List<String> warnings = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(warnings).hasSize(2);
            assertThat(warnings.get(0)).contains("[0]").contains("required fields are missing");
            assertThat(warnings.get(1)).contains("[1]").contains("unknown citation");
        } finally {
            logger.detachAppender(appender);
        }
    }

    // 非空列表被全数丢弃仍是硬错误:全垃圾输出不得伪装成"零发现的干净结果"。
    @Test
    void rejectsWhenEveryFindingCarriesFabricatedOrDuplicateCitation() {
        when(audit.save(any())).thenAnswer(call -> call.getArgument(0));
        when(client.generate(prompt)).thenReturn(response("""
                {"findings":[
                {"severity":"HIGH","category":"security","title":"Claim","description":"D",
                "filePath":"src/Main.java","lineStart":10,"lineEnd":10,"symbol":"run","ruleId":"X",
                "citationIds":["fabricated"]},
                {"severity":"HIGH","category":"security","title":"Doubled","description":"D",
                "citationIds":["rule#chunk-1","rule#chunk-1"]}]}
                """));

        assertThatThrownBy(() -> service.generate(1L, client, prompt, Set.of("rule#chunk-1")))
                .isInstanceOf(AgentStepExecutionException.class)
                .hasMessageContaining("dropped as invalid")
                .satisfies(error -> assertThat(((AgentStepExecutionException) error).getFailureType())
                        .isEqualTo(AgentFailureType.INVALID_MODEL_OUTPUT));
    }

    // 原始就是空列表:零发现是合法的干净结果,不得因裁剪逻辑误伤。
    @Test
    void emptyFindingsListRemainsALegalCleanResult() {
        when(audit.save(any())).thenAnswer(call -> call.getArgument(0));
        when(client.generate(prompt)).thenReturn(response("{\"findings\":[]}"));

        AgentFindingModelService.Result result = service.generate(
                1L, client, prompt, Set.of("rule#chunk-1")
        );

        assertThat(result.response().findings()).isEmpty();
    }

    // run16 实证(2026-08-09):无知识项目检索证据为空,citation 白名单为空集,原规则仍
    // 强制每条 finding ≥1 引用——无解约束,该步在无知识项目上永远失败。分档后:白名单
    // 为空,零引用的 finding 存活。
    @Test
    void emptyWhitelistKeepsFindingsWithoutCitations() {
        when(audit.save(any())).thenAnswer(call -> call.getArgument(0));
        when(client.generate(prompt)).thenReturn(response("""
                {"findings":[{"severity":"HIGH","category":"security","title":"SQL leak",
                "description":"Connection is not closed","filePath":"src/Main.java",
                "lineStart":10,"lineEnd":10,"symbol":"run","ruleId":"JAVA-1",
                "citationIds":[]}]}
                """));

        AgentFindingModelService.Result result = service.generate(1L, client, prompt, Set.of());

        assertThat(result.response().findings()).singleElement()
                .extracting(FindingModelResponse.ModelFinding::title)
                .isEqualTo("SQL leak");
    }

    // 白名单为空时零引用即合法,但带引用仍丢:无知识可引却带 citation,只能是凭空捏造。
    @Test
    void emptyWhitelistStillDropsFindingsCarryingFabricatedCitations() {
        when(audit.save(any())).thenAnswer(call -> call.getArgument(0));
        when(client.generate(prompt)).thenReturn(response("""
                {"findings":[
                {"severity":"HIGH","category":"security","title":"Fabricated","description":"D",
                "citationIds":["made-up"]},
                {"severity":"LOW","category":"style","title":"Kept","description":"D",
                "filePath":"src/Main.java","lineStart":2,"lineEnd":2,"symbol":"run","ruleId":"R",
                "citationIds":[]}]}
                """));

        AgentFindingModelService.Result result = service.generate(1L, client, prompt, Set.of());

        assertThat(result.response().findings()).singleElement()
                .extracting(FindingModelResponse.ModelFinding::title)
                .isEqualTo("Kept");
    }

    // 白名单为空 + 全部带捏造引用 → 全数被裁,"非空全灭"硬错保持不变(run16 原始现场;
    // 提示词分支修正后模型仍全量编引用即属真实劣化,不得伪装成干净结果)。
    @Test
    void emptyWhitelistWithAllFabricatedCitationsRemainsAHardError() {
        when(audit.save(any())).thenAnswer(call -> call.getArgument(0));
        when(client.generate(prompt)).thenReturn(response("""
                {"findings":[
                {"severity":"HIGH","category":"security","title":"A","description":"D",
                "citationIds":["made-up-1"]},
                {"severity":"HIGH","category":"security","title":"B","description":"D",
                "citationIds":["made-up-2"]}]}
                """));

        assertThatThrownBy(() -> service.generate(1L, client, prompt, Set.of()))
                .isInstanceOf(AgentStepExecutionException.class)
                .hasMessageContaining("dropped as invalid")
                .satisfies(error -> assertThat(((AgentStepExecutionException) error).getFailureType())
                        .isEqualTo(AgentFailureType.INVALID_MODEL_OUTPUT));
    }

    // 白名单非空的档位回归:缺引用照旧丢,分档只放宽"无知识可引"的场景。
    @Test
    void nonEmptyWhitelistStillRequiresAtLeastOneCitation() {
        when(audit.save(any())).thenAnswer(call -> call.getArgument(0));
        when(client.generate(prompt)).thenReturn(response("""
                {"findings":[
                {"severity":"HIGH","category":"security","title":"Uncited","description":"D",
                "citationIds":[]},
                {"severity":"LOW","category":"style","title":"Kept","description":"D",
                "filePath":"src/Main.java","lineStart":2,"lineEnd":2,"symbol":"run","ruleId":"R",
                "citationIds":["rule#chunk-1"]}]}
                """));

        AgentFindingModelService.Result result = service.generate(
                1L, client, prompt, Set.of("rule#chunk-1")
        );

        assertThat(result.response().findings()).singleElement()
                .extracting(FindingModelResponse.ModelFinding::title)
                .isEqualTo("Kept");
    }

    private AgentModelClient.ModelResponse response(String content) {
        return new AgentModelClient.ModelResponse(
                "fixture", "fixture", content, 100, 50, "STOP", 10
        );
    }
}
