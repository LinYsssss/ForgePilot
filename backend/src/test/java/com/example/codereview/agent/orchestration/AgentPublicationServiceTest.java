package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.example.codereview.common.security.CryptoService;
import com.example.codereview.finding.FindingDecisionRepository;
import com.example.codereview.finding.FindingRepository;
import com.example.codereview.patch.PatchApprovalRepository;
import com.example.codereview.patch.PatchCandidateRepository;
import com.example.codereview.scm.NormalizedPullRequestEvent;
import com.example.codereview.scm.ScmInstallation;
import com.example.codereview.scm.ScmInstallationRepository;
import com.example.codereview.scm.ScmPublicationResult;
import com.example.codereview.scm.ScmProviderType;
import com.example.codereview.scm.ScmReviewPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentPublicationServiceTest {

    @Test
    void duplicatePublicationKeyCallsProviderOnlyOnce() {
        AgentScmContextRepository scmContexts = mock(AgentScmContextRepository.class);
        ScmInstallationRepository installations = mock(ScmInstallationRepository.class);
        AgentPublicationRepository publications = mock(AgentPublicationRepository.class);
        FindingRepository findings = mock(FindingRepository.class);
        FindingDecisionRepository decisions = mock(FindingDecisionRepository.class);
        PatchCandidateRepository patches = mock(PatchCandidateRepository.class);
        PatchApprovalRepository approvals = mock(PatchApprovalRepository.class);
        CryptoService crypto = mock(CryptoService.class);
        ScmReviewPublisher publisher = mock(ScmReviewPublisher.class);
        AgentScmContext context = AgentScmContext.from(1L, event(), installation(11L));
        AgentPublication record = new AgentPublication(1L, "scm-publication:1:head:none");
        record.record(new ScmPublicationResult(true, List.of(201, 201), "ok"));
        when(scmContexts.findByAgentRunId(1L)).thenReturn(Optional.of(context));
        when(installations.findById(11L)).thenReturn(Optional.of(installation(11L)));
        when(publications.findByIdempotencyKey("scm-publication:1:head:none"))
                .thenReturn(Optional.empty(), Optional.of(record));
        when(publications.save(any())).thenAnswer(call -> call.getArgument(0));
        when(crypto.decrypt("encrypted")).thenReturn("credential");
        when(publisher.type()).thenReturn(ScmProviderType.GITHUB);
        when(publisher.publish(any(), any())).thenReturn(new ScmPublicationResult(true, List.of(201, 201), "ok"));

        AgentPublicationService service = new AgentPublicationService(
                scmContexts, installations, publications, findings, decisions, patches, approvals,
                crypto, List.of(publisher), new ObjectMapper(), "http://localhost"
        );

        assertThat(service.publish(1L, "head").isPublished()).isTrue();
        assertThat(service.publish(1L, "head").isPublished()).isTrue();
        verify(publisher, times(1)).publish(any(), any());
    }

    // run17 实证:无凭证 installation 是合法演示姿态,远端投递显式跳过而非按生产投递硬失败。
    // 渲染与落库照常,publisher 与解密全程零交互。
    @Test
    void missingCredentialSkipsRemoteDeliveryAndRecordsPublication() {
        AgentScmContextRepository scmContexts = mock(AgentScmContextRepository.class);
        ScmInstallationRepository installations = mock(ScmInstallationRepository.class);
        AgentPublicationRepository publications = mock(AgentPublicationRepository.class);
        FindingRepository findings = mock(FindingRepository.class);
        FindingDecisionRepository decisions = mock(FindingDecisionRepository.class);
        PatchCandidateRepository patches = mock(PatchCandidateRepository.class);
        PatchApprovalRepository approvals = mock(PatchApprovalRepository.class);
        CryptoService crypto = mock(CryptoService.class);
        ScmReviewPublisher publisher = mock(ScmReviewPublisher.class);
        ScmInstallation installation = credentiallessInstallation(11L);
        AgentScmContext context = AgentScmContext.from(1L, event(), installation);
        when(scmContexts.findByAgentRunId(1L)).thenReturn(Optional.of(context));
        when(installations.findById(11L)).thenReturn(Optional.of(installation));
        when(publications.findByIdempotencyKey("scm-publication:1:head:none")).thenReturn(Optional.empty());
        when(publications.save(any())).thenAnswer(call -> call.getArgument(0));

        AgentPublicationService service = new AgentPublicationService(
                scmContexts, installations, publications, findings, decisions, patches, approvals,
                crypto, List.of(publisher), new ObjectMapper(), "http://localhost"
        );

        AgentPublication record = service.publish(1L, "head");

        assertThat(record.isPublished()).isTrue();
        assertThat(record.getMessage())
                .contains("SCM delivery skipped")
                .contains("no credential");
        // 首次 save 建 PENDING 记录,第二次 save 落盘跳过结果——发布记录可审计。
        verify(publications, times(2)).save(any());
        verifyNoInteractions(publisher, crypto);
    }

    // run17 盲错修复:一句 "SCM publication failed" 分不出 "null baseUrl 还是 401",
    // 错误信息必须携带底层原因片段;非瞬态异常仍按 PERMANENT 分类。
    @Test
    void runtimeFailureMessageCarriesUnderlyingCause() {
        AgentStepExecutionException failure = publishFailure(
                new IllegalStateException("api.github.com: 404 repository not found"));

        assertThat(failure.getMessage())
                .contains("SCM publication failed")
                .contains("api.github.com: 404 repository not found");
        assertThat(failure.getFailureType()).isEqualTo(AgentFailureType.PERMANENT_PROVIDER_ERROR);
    }

    // retryable 分类逻辑不因盲错修复而改变:cause 链上的 Timeout 仍判为可重试。
    @Test
    void timeoutFailureRemainsRetryableWithDetail() {
        AgentStepExecutionException failure = publishFailure(
                new IllegalStateException("delivery aborted", new SocketTimeoutException("read timed out")));

        assertThat(failure.getMessage()).contains("delivery aborted");
        assertThat(failure.getFailureType()).isEqualTo(AgentFailureType.RETRYABLE_PROVIDER_ERROR);
    }

    private AgentStepExecutionException publishFailure(RuntimeException publisherFailure) {
        AgentScmContextRepository scmContexts = mock(AgentScmContextRepository.class);
        ScmInstallationRepository installations = mock(ScmInstallationRepository.class);
        AgentPublicationRepository publications = mock(AgentPublicationRepository.class);
        FindingRepository findings = mock(FindingRepository.class);
        FindingDecisionRepository decisions = mock(FindingDecisionRepository.class);
        PatchCandidateRepository patches = mock(PatchCandidateRepository.class);
        PatchApprovalRepository approvals = mock(PatchApprovalRepository.class);
        CryptoService crypto = mock(CryptoService.class);
        ScmReviewPublisher publisher = mock(ScmReviewPublisher.class);
        AgentScmContext context = AgentScmContext.from(1L, event(), installation(11L));
        when(scmContexts.findByAgentRunId(1L)).thenReturn(Optional.of(context));
        when(installations.findById(11L)).thenReturn(Optional.of(installation(11L)));
        when(publications.findByIdempotencyKey("scm-publication:1:head:none")).thenReturn(Optional.empty());
        when(publications.save(any())).thenAnswer(call -> call.getArgument(0));
        when(crypto.decrypt("encrypted")).thenReturn("credential");
        when(publisher.type()).thenReturn(ScmProviderType.GITHUB);
        when(publisher.publish(any(), any())).thenThrow(publisherFailure);

        AgentPublicationService service = new AgentPublicationService(
                scmContexts, installations, publications, findings, decisions, patches, approvals,
                crypto, List.of(publisher), new ObjectMapper(), "http://localhost"
        );

        AgentStepExecutionException thrown = catchThrowableOfType(
                AgentStepExecutionException.class, () -> service.publish(1L, "head"));
        assertThat(thrown).isNotNull();
        return thrown;
    }

    private NormalizedPullRequestEvent event() {
        return new NormalizedPullRequestEvent(
                ScmProviderType.GITHUB, "install", "org/repo", "https://github.com/org/repo.git",
                7, "title", "author", "feature", "main", "base", "head", "opened", "delivery"
        );
    }

    private ScmInstallation installation(Long id) {
        ScmInstallation value = new ScmInstallation();
        value.setId(id);
        value.setProvider(ScmProviderType.GITHUB);
        value.setApiBaseUrl("https://api.github.com");
        value.setEncryptedCredential("encrypted");
        value.setActive(true);
        return value;
    }

    /** run17 实况:本地演示 installation 无凭证也无 apiBaseUrl,仍是激活状态的合法姿态。 */
    private ScmInstallation credentiallessInstallation(Long id) {
        ScmInstallation value = new ScmInstallation();
        value.setId(id);
        value.setProvider(ScmProviderType.GITHUB);
        value.setActive(true);
        return value;
    }
}
