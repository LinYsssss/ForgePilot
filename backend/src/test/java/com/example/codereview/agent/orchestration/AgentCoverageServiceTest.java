package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.git.GitCliService;
import com.example.codereview.repo.CodeRepositoryEntity;
import com.example.codereview.repo.CodeRepositoryJpaRepository;
import com.example.codereview.repo.RepositoryDtos.CommitDiffResponse;
import com.example.codereview.review.CoverageJudgeService;
import com.example.codereview.scm.NormalizedPullRequestEvent;
import com.example.codereview.scm.ScmInstallation;
import com.example.codereview.scm.ScmProviderType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * P4b(A1):Agent 链路 coverage 是 best-effort 增强——所有失败路径静默返回空行集且不抛;
 * 成功路径把结论落 run 并产出人读摘要行。
 */
class AgentCoverageServiceTest {

    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final AgentScmContextRepository scmContexts = mock(AgentScmContextRepository.class);
    private final CodeRepositoryJpaRepository repositories = mock(CodeRepositoryJpaRepository.class);
    private final GitCliService gitCliService = mock(GitCliService.class);
    private final CoverageJudgeService coverageJudgeService = mock(CoverageJudgeService.class);

    private final AgentCoverageService service = new AgentCoverageService(
            runs, scmContexts, repositories, gitCliService, coverageJudgeService, new ObjectMapper());

    @Test
    void everyFailurePathIsSilentlyEmpty() {
        // run 不存在
        when(runs.findById(1L)).thenReturn(Optional.empty());
        assertThat(service.judgeAndAttach(1L, "head")).isEmpty();

        // 无 SCM 上下文
        AgentRun run = new AgentRun(5L, 9L, null, "t", "head");
        when(runs.findById(2L)).thenReturn(Optional.of(run));
        when(scmContexts.findByAgentRunId(2L)).thenReturn(Optional.empty());
        assertThat(service.judgeAndAttach(2L, "head")).isEmpty();

        // 仓库缺失
        when(runs.findById(3L)).thenReturn(Optional.of(run));
        when(scmContexts.findByAgentRunId(3L)).thenReturn(Optional.of(context()));
        when(repositories.findById(9L)).thenReturn(Optional.empty());
        assertThat(service.judgeAndAttach(3L, "head")).isEmpty();

        // diff 失败(git 异常)
        when(repositories.findById(9L)).thenReturn(Optional.of(repository()));
        when(gitCliService.diff(any(), anyString(), anyString())).thenThrow(new IllegalStateException("git down"));
        assertThat(service.judgeAndAttach(3L, "head")).isEmpty();
    }

    @Test
    void successPathAttachesCoverageAndRendersSummaryLines() {
        AgentRun run = new AgentRun(5L, 9L, null, "t", "head");
        when(runs.findById(7L)).thenReturn(Optional.of(run));
        when(scmContexts.findByAgentRunId(7L)).thenReturn(Optional.of(context()));
        when(repositories.findById(9L)).thenReturn(Optional.of(repository()));
        when(gitCliService.diff(any(), anyString(), anyString()))
                .thenReturn(new CommitDiffResponse("head", "base", List.of(), "diff --git a/A b/A"));
        String coverageJson = """
                {"requirementId":1,"requirementCode":"REQ-1","requirementTitle":"订单取消库存释放",
                 "coverage":[{"acId":"AC1","acText":"取消后回补","verdict":"COVERED","evidence":[],"rationale":"ok"},
                             {"acId":"AC2","acText":"重复取消不重复回补","verdict":"NOT_FOUND","evidence":[],"rationale":"miss"}]}
                """;
        when(coverageJudgeService.judgeForRefs(eq(5L), any(), eq("PR#12"), any(), anyString(), any(), eq(true)))
                .thenReturn(coverageJson);

        List<String> lines = service.judgeAndAttach(7L, "head");

        assertThat(run.getCoverageJson()).isEqualTo(coverageJson);
        verify(runs).save(run);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).contains("REQ-1");
        assertThat(lines.get(1)).contains("AC1").contains("已覆盖");
        assertThat(lines.get(2)).contains("AC2").contains("未发现");
    }

    private AgentScmContext context() {
        return AgentScmContext.from(1L, new NormalizedPullRequestEvent(
                ScmProviderType.GITHUB, "inst-1", "acme/app", "https://example.com/acme/app.git",
                12, "REQ-1 释放库存", "dev", "REQ-1-branch", "main", "base", "head", "opened", "d-1"),
                installation());
    }

    private ScmInstallation installation() {
        ScmInstallation value = new ScmInstallation();
        value.setId(11L);
        value.setProvider(ScmProviderType.GITHUB);
        value.setActive(true);
        return value;
    }

    private CodeRepositoryEntity repository() {
        return new CodeRepositoryEntity(5L, "https://example.com/acme/app.git", "GITHUB", "main", "enc");
    }
}
