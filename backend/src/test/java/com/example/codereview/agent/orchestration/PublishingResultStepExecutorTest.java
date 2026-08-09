package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.orchestration.steps.PublishingResultStepExecutor;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.example.codereview.agent.run.AgentRunStatus;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 归档生命周期的发布侧钉子(F-04 收尾):结果发布成功后就地清理本 Run 的工作区归档,
 * 发布失败则一律不清理——FAILED/TIMED_OUT 的归档按 ADR 0001 留给运维续跑,由 TTL 清扫兜底。
 * run18 已端到端实证该行为,此处把时序契约固定在单元层,防止清理调用被挪动或误删。
 */
class PublishingResultStepExecutorTest {

    @Test
    void cleansUpRunArchivesOnlyAfterSuccessfulPublication() {
        AgentPublicationService publications = mock(AgentPublicationService.class);
        WorkspaceArchiveService workspaceArchives = mock(WorkspaceArchiveService.class);
        AgentPublication publication = mock(AgentPublication.class);
        when(publication.getId()).thenReturn(7L);
        when(publication.getStatus()).thenReturn("PUBLISHED");
        when(publications.publish(1L, "abcdef1")).thenReturn(publication);

        AgentStepResult result = new PublishingResultStepExecutor(publications, workspaceArchives)
                .execute(context());

        assertThat(result.nextState()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(result.output()).containsEntry("publicationStatus", "PUBLISHED");
        // 先发布、后清理:清理动作以"结果已经落定"为前提,顺序不可颠倒。
        InOrder order = inOrder(publications, workspaceArchives);
        order.verify(publications).publish(1L, "abcdef1");
        order.verify(workspaceArchives).cleanupForRun(1L, "abcdef1");
    }

    @Test
    void publicationFailureLeavesArchivesForRetry() {
        AgentPublicationService publications = mock(AgentPublicationService.class);
        WorkspaceArchiveService workspaceArchives = mock(WorkspaceArchiveService.class);
        when(publications.publish(1L, "abcdef1")).thenThrow(new AgentStepExecutionException(
                AgentFailureType.RETRYABLE_PROVIDER_ERROR, "SCM publication failed: 502"
        ));

        assertThatThrownBy(() -> new PublishingResultStepExecutor(publications, workspaceArchives)
                .execute(context()))
                .isInstanceOf(AgentStepExecutionException.class);

        verifyNoInteractions(workspaceArchives);
    }

    private AgentStepExecutionContext context() {
        return new AgentStepExecutionContext(
                1L, 17L, 2L, 3L, 4L, "abcdef1",
                AgentRunStatus.PUBLISHING_RESULT, 9, 0, "trace", false
        );
    }
}
