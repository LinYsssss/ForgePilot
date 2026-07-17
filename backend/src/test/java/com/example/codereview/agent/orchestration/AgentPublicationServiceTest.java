package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
