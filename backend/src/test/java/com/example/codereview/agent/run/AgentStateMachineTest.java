package com.example.codereview.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AgentStateMachineTest {

    private final AgentStateMachine stateMachine = new AgentStateMachine();

    @Test
    void acceptsMainPathAndDocumentedBranches() {
        assertAllowed(AgentRunStatus.RECEIVED, AgentRunStatus.PREPARING_REPOSITORY);
        assertAllowed(AgentRunStatus.PREPARING_REPOSITORY, AgentRunStatus.ANALYZING_CHANGE);
        assertAllowed(AgentRunStatus.ANALYZING_CHANGE, AgentRunStatus.PLANNING);
        assertAllowed(AgentRunStatus.PLANNING, AgentRunStatus.EXECUTING_TOOLS);
        assertAllowed(AgentRunStatus.EXECUTING_TOOLS, AgentRunStatus.RETRIEVING_CONTEXT);
        assertAllowed(AgentRunStatus.RETRIEVING_CONTEXT, AgentRunStatus.VERIFYING_FINDINGS);
        assertAllowed(AgentRunStatus.VERIFYING_FINDINGS, AgentRunStatus.GENERATING_PATCH);
        assertAllowed(AgentRunStatus.GENERATING_PATCH, AgentRunStatus.VALIDATING_PATCH);
        assertAllowed(AgentRunStatus.VALIDATING_PATCH, AgentRunStatus.WAITING_APPROVAL);
        assertAllowed(AgentRunStatus.WAITING_APPROVAL, AgentRunStatus.PUBLISHING_RESULT);
        assertAllowed(AgentRunStatus.PUBLISHING_RESULT, AgentRunStatus.COMPLETED);

        assertAllowed(AgentRunStatus.VERIFYING_FINDINGS, AgentRunStatus.PUBLISHING_RESULT);
        assertAllowed(AgentRunStatus.GENERATING_PATCH, AgentRunStatus.PUBLISHING_RESULT);
        assertAllowed(AgentRunStatus.VALIDATING_PATCH, AgentRunStatus.PUBLISHING_RESULT);
    }

    @Test
    void rejectsTerminalAndApprovalBypassTransitions() {
        assertRejected(AgentRunStatus.COMPLETED, AgentRunStatus.EXECUTING_TOOLS);
        assertRejected(AgentRunStatus.CANCELED, AgentRunStatus.RETRY_WAIT);
        assertRejected(AgentRunStatus.WAITING_APPROVAL, AgentRunStatus.COMPLETED);
    }

    @Test
    void permitsRetryableWorkToWaitButNotTerminalStates() {
        assertAllowed(AgentRunStatus.EXECUTING_TOOLS, AgentRunStatus.RETRY_WAIT);
        assertAllowed(AgentRunStatus.RETRY_WAIT, AgentRunStatus.EXECUTING_TOOLS);
        assertRejected(AgentRunStatus.FAILED, AgentRunStatus.RETRY_WAIT);
        assertRejected(AgentRunStatus.TIMED_OUT, AgentRunStatus.PUBLISHING_RESULT);
    }

    private void assertAllowed(AgentRunStatus from, AgentRunStatus to) {
        assertThat(stateMachine.canTransition(from, to)).isTrue();
        stateMachine.requireTransition(from, to);
    }

    private void assertRejected(AgentRunStatus from, AgentRunStatus to) {
        assertThat(stateMachine.canTransition(from, to)).isFalse();
        assertThatThrownBy(() -> stateMachine.requireTransition(from, to))
                .isInstanceOf(IllegalAgentTransitionException.class)
                .hasMessageContaining(from.name())
                .hasMessageContaining(to.name());
    }
}
