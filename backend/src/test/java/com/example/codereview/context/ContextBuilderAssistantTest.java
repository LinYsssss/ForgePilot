package com.example.codereview.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.common.security.ProjectAuthorization;
import com.example.codereview.knowledge.KnowledgeDtos.SearchMatch;
import com.example.codereview.git.GitCliService;
import com.example.codereview.pullrequest.PullRequestRepository;
import com.example.codereview.rag.RagService;
import com.example.codereview.repo.CodeRepositoryJpaRepository;
import com.example.codereview.requirement.AcceptanceCriterionEntity;
import com.example.codereview.requirement.AcceptanceCriterionRepository;
import com.example.codereview.requirement.RequirementEntity;
import com.example.codereview.requirement.RequirementLinkEntity;
import com.example.codereview.requirement.RequirementLinkRepository;
import com.example.codereview.requirement.RequirementRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ContextBuilderAssistantTest {

    @Test
    void keepsRequiredRequirementAndAcWhenOptionalKnowledgeFails() {
        RagService rag = mock(RagService.class);
        ProjectAuthorization auth = mock(ProjectAuthorization.class);
        RequirementRepository requirements = mock(RequirementRepository.class);
        AcceptanceCriterionRepository criteria = mock(AcceptanceCriterionRepository.class);
        RequirementLinkRepository links = mock(RequirementLinkRepository.class);
        RequirementEntity requirement = new RequirementEntity(3L, 9L, "库存释放", "背景", "描述", "HIGH", 1L);
        when(requirements.findByIdAndProjectId(8L, 3L)).thenReturn(Optional.of(requirement));
        when(criteria.findByRequirementIdOrderBySeqAsc(null))
                .thenReturn(List.of(new AcceptanceCriterionEntity(null, 1, "五分钟内回补")));
        when(links.findByRequirementIdOrderByCreatedAtAsc(null)).thenReturn(List.of());
        doThrow(new IllegalStateException("embedding unavailable")).when(rag).search(3L, "库存释放\n背景\n描述\n五分钟内回补", 5);
        ContextBuilder builder = new ContextBuilder(rag, auth, requirements, criteria, links,
                mock(CodeRepositoryJpaRepository.class), mock(PullRequestRepository.class), mock(GitCliService.class),
                5, 1200, 4000, 4, 12, 3000, 12000);

        ContextBuilder.ContextBundle result = builder.build(ContextScene.ASSISTANT, 3L,
                ContextBuilder.Refs.assistant(4L, 8L));

        assertThat(result.requirement().sourceId()).isEqualTo("REQ-9");
        assertThat(result.requirement().acceptanceCriteria()).extracting(ContextBuilder.AcceptanceCriterion::sourceId)
                .containsExactly("AC-1");
        assertThat(result.warnings()).singleElement().asString().contains("知识库检索暂不可用");
        assertThat(result.sources()).extracting(ContextBuilder.Source::id).contains("REQ-9", "AC-1");
    }
    @Test
    void boundsAndRedactsKnowledgeQueryAndUsesOpaqueKnowledgeSourceId() {
        RagService rag = mock(RagService.class);
        ProjectAuthorization auth = mock(ProjectAuthorization.class);
        RequirementRepository requirements = mock(RequirementRepository.class);
        AcceptanceCriterionRepository criteria = mock(AcceptanceCriterionRepository.class);
        RequirementLinkRepository links = mock(RequirementLinkRepository.class);
        RequirementEntity requirement = new RequirementEntity(3L, 9L, "库存释放", "API_TOKEN=secret",
                "很长描述".repeat(30), "HIGH", 1L);
        when(requirements.findByIdAndProjectId(8L, 3L)).thenReturn(Optional.of(requirement));
        when(criteria.findByRequirementIdOrderBySeqAsc(null)).thenReturn(List.of());
        when(links.findByRequirementIdOrderByCreatedAtAsc(null)).thenReturn(List.of());
        when(rag.search(eq(3L), anyString(), eq(5))).thenReturn(List.of(
                new SearchMatch(55L, "ghp_12345678901234567890.md", "MARKDOWN", 0, 1.0, "内容")));
        ContextBuilder builder = new ContextBuilder(rag, auth, requirements, criteria, links,
                mock(CodeRepositoryJpaRepository.class), mock(PullRequestRepository.class), mock(GitCliService.class),
                5, 1200, 32, 4, 12, 3000, 12000);

        ContextBuilder.ContextBundle result = builder.build(ContextScene.ASSISTANT, 3L,
                ContextBuilder.Refs.assistant(4L, 8L));

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(rag).search(eq(3L), query.capture(), eq(5));
        assertThat(query.getValue()).hasSizeLessThanOrEqualTo(32).doesNotContain("secret");
        assertThat(result.sources()).extracting(ContextBuilder.Source::id).contains("KB:55");
    }

    @Test
    void branchMetadataAlsoHonorsTheCodeLinkItemBudget() {
        RagService rag = mock(RagService.class);
        ProjectAuthorization auth = mock(ProjectAuthorization.class);
        RequirementRepository requirements = mock(RequirementRepository.class);
        AcceptanceCriterionRepository criteria = mock(AcceptanceCriterionRepository.class);
        RequirementLinkRepository links = mock(RequirementLinkRepository.class);
        CodeRepositoryJpaRepository repositories = mock(CodeRepositoryJpaRepository.class);
        RequirementEntity requirement = new RequirementEntity(3L, 9L, "库存释放", "", "", "HIGH", 1L);
        when(requirements.findByIdAndProjectId(8L, 3L)).thenReturn(Optional.of(requirement));
        when(criteria.findByRequirementIdOrderBySeqAsc(null)).thenReturn(List.of());
        when(rag.search(3L, "库存释放\n\n", 5)).thenReturn(List.of());
        when(links.findByRequirementIdOrderByCreatedAtAsc(null)).thenReturn(List.of(
                new RequirementLinkEntity(3L, null, "BRANCH", "one", "AUTO"),
                new RequirementLinkEntity(3L, null, "BRANCH", "two", "AUTO"),
                new RequirementLinkEntity(3L, null, "BRANCH", "three", "AUTO")));
        when(repositories.findByProjectId(3L)).thenReturn(Optional.empty());
        ContextBuilder builder = new ContextBuilder(rag, auth, requirements, criteria, links, repositories,
                mock(PullRequestRepository.class), mock(GitCliService.class),
                5, 1200, 4000, 2, 12, 3000, 12000);

        ContextBuilder.ContextBundle result = builder.build(ContextScene.ASSISTANT, 3L,
                ContextBuilder.Refs.assistant(4L, 8L));

        assertThat(result.sources()).filteredOn(source -> "CODE_LINK".equals(source.type()))
                .extracting(ContextBuilder.Source::id)
                .containsExactly("CODE:BRANCH:1", "CODE:BRANCH:2");
        assertThat(result.truncatedSections()).contains("code_links");
    }

}
