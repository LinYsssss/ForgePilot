package com.example.codereview.finding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.finding.AgentFindingDtos.AgentFindingResponse;
import com.example.codereview.project.ProjectService;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentFindingQueryServiceTest {

    private static final Long PROJECT_ID = 5L;
    private static final Long RUN_ID = 7L;
    private static final Long USER_ID = 11L;

    private final FindingRepository findings = mock(FindingRepository.class);
    private final FindingEvidenceRepository evidences = mock(FindingEvidenceRepository.class);
    private final FindingDecisionRepository decisions = mock(FindingDecisionRepository.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private AgentFindingQueryService service;

    @BeforeEach
    void setUp() {
        service = new AgentFindingQueryService(findings, evidences, decisions, runs, projectService);
    }

    /** 实体的 id 由 JPA 生成,单测里只能反射注入。 */
    private static <T> T withId(T entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
            return entity;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Finding finding(Long id, String status) {
        return withId(new Finding(RUN_ID, FindingSeverity.HIGH, "security", "发货未校验支付状态",
                "forceShip 直接改状态", "src/OrderService.java", 21, 28, "forceShip", status), id);
    }

    private FindingEvidenceEntity evidence(Long findingId, String path, Integer start, Integer end) {
        return FindingEvidenceEntity.from(findingId, FindingEvidence.create(
                EvidenceType.CODE_LOCATION, "abc1234", path, start, end, "order.setStatus(SHIPPED)", 0.9));
    }

    private FindingDecisionEntity decision(Long findingId, double confidence, boolean blocking, String version) {
        return FindingDecisionEntity.from(findingId,
                new GateDecision(blocking, "reason-" + version, confidence, 0.75, version, List.of()));
    }

    @Test
    void unknownRunAndForeignRunAreNotFound() {
        when(runs.findById(RUN_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(PROJECT_ID, RUN_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(404));

        // run 存在但属于别的项目
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(new AgentRun(99L, 2L, 3L, "trigger", "head")));
        assertThatThrownBy(() -> service.list(PROJECT_ID, RUN_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(404));
    }

    @Test
    void emptyRunShortCircuitsWithoutQueryingEvidence() {
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(new AgentRun(PROJECT_ID, 2L, 3L, "trigger", "head")));
        when(findings.findByAgentRunIdOrderByIdAsc(RUN_ID)).thenReturn(List.of());

        assertThat(service.list(PROJECT_ID, RUN_ID, USER_ID)).isEmpty();
        // 没有 finding 时不该再打证据/裁决表
        org.mockito.Mockito.verify(evidences, org.mockito.Mockito.never()).findByFindingIdInOrderByIdAsc(anyCollection());
    }

    @Test
    void latestDecisionWinsAndEvidenceIsGroupedPerFinding() {
        Finding first = finding(1L, "verified");
        Finding second = finding(2L, "verified");
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(new AgentRun(PROJECT_ID, 2L, 3L, "trigger", "head")));
        when(findings.findByAgentRunIdOrderByIdAsc(RUN_ID)).thenReturn(List.of(first, second));
        when(evidences.findByFindingIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(
                evidence(1L, "src/OrderService.java", 21, 28),
                evidence(1L, "docs/policy.md", 10, 10),
                evidence(2L, null, null, null)));
        // 同一 finding 两次裁决,按 id 升序返回,应取最后一条
        when(decisions.findByFindingIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(
                decision(1L, 0.40, false, "v0"),
                decision(1L, 0.86, true, "v1")));

        List<AgentFindingResponse> result = service.list(PROJECT_ID, RUN_ID, USER_ID);

        assertThat(result).hasSize(2);
        AgentFindingResponse one = result.get(0);
        assertThat(one.confidence()).isEqualTo(0.86);
        assertThat(one.blocking()).isTrue();
        assertThat(one.weightVersion()).isEqualTo("v1");
        assertThat(one.evidence()).hasSize(2);

        // 没有裁决的 finding 不该被当成可阻断
        AgentFindingResponse two = result.get(1);
        assertThat(two.confidence()).isNull();
        assertThat(two.blocking()).isFalse();
        assertThat(two.evidence()).hasSize(1);
    }

    @Test
    void citationReferenceCoversRangeSingleLineAndMissingPath() {
        Finding only = finding(1L, "verified");
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(new AgentRun(PROJECT_ID, 2L, 3L, "trigger", "head")));
        when(findings.findByAgentRunIdOrderByIdAsc(RUN_ID)).thenReturn(List.of(only));
        when(evidences.findByFindingIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(
                evidence(1L, "src/OrderService.java", 21, 28),   // 跨行
                evidence(1L, "docs/policy.md", 10, 10),          // 单行
                evidence(1L, null, null, null)));                // 无路径 → 回退 sourceVersion
        when(decisions.findByFindingIdInOrderByIdAsc(anyCollection())).thenReturn(List.of());

        List<String> refs = service.list(PROJECT_ID, RUN_ID, USER_ID).get(0).evidence().stream()
                .map(AgentFindingDtos.EvidenceResponse::reference).toList();

        assertThat(refs).containsExactly("src/OrderService.java:21-28", "docs/policy.md:10", "abc1234");
    }

    @Test
    void rejectedFindingsAreReturnedWithTheirStatusSoCallersCanTellThemApart() {
        Finding verified = finding(1L, "verified");
        Finding rejected = finding(2L, "rejected");
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(new AgentRun(PROJECT_ID, 2L, 3L, "trigger", "head")));
        when(findings.findByAgentRunIdOrderByIdAsc(RUN_ID)).thenReturn(List.of(verified, rejected));
        when(evidences.findByFindingIdInOrderByIdAsc(anyCollection())).thenReturn(List.of());
        when(decisions.findByFindingIdInOrderByIdAsc(anyCollection())).thenReturn(List.of());

        List<AgentFindingResponse> result = service.list(PROJECT_ID, RUN_ID, USER_ID);

        assertThat(result).extracting(AgentFindingResponse::status)
                .containsExactly("verified", "rejected");
    }
}
