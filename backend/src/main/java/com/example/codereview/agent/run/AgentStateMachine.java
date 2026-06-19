package com.example.codereview.agent.run;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AgentStateMachine {

    private static final Set<AgentRunStatus> TERMINAL = Set.of(
            AgentRunStatus.COMPLETED,
            AgentRunStatus.FAILED,
            AgentRunStatus.CANCELED,
            AgentRunStatus.TIMED_OUT
    );

    private static final Map<AgentRunStatus, Set<AgentRunStatus>> TRANSITIONS = buildTransitions();

    public boolean canTransition(AgentRunStatus from, AgentRunStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public void requireTransition(AgentRunStatus from, AgentRunStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalAgentTransitionException(from, to);
        }
    }

    public boolean isTerminal(AgentRunStatus status) {
        return TERMINAL.contains(status);
    }

    private static Map<AgentRunStatus, Set<AgentRunStatus>> buildTransitions() {
        EnumMap<AgentRunStatus, Set<AgentRunStatus>> transitions = new EnumMap<>(AgentRunStatus.class);
        add(transitions, AgentRunStatus.RECEIVED, AgentRunStatus.PREPARING_REPOSITORY);
        add(transitions, AgentRunStatus.PREPARING_REPOSITORY, AgentRunStatus.ANALYZING_CHANGE);
        add(transitions, AgentRunStatus.ANALYZING_CHANGE, AgentRunStatus.PLANNING);
        add(transitions, AgentRunStatus.PLANNING, AgentRunStatus.EXECUTING_TOOLS);
        add(transitions, AgentRunStatus.EXECUTING_TOOLS, AgentRunStatus.RETRIEVING_CONTEXT);
        add(transitions, AgentRunStatus.RETRIEVING_CONTEXT, AgentRunStatus.VERIFYING_FINDINGS);
        add(transitions, AgentRunStatus.VERIFYING_FINDINGS,
                AgentRunStatus.GENERATING_PATCH, AgentRunStatus.PUBLISHING_RESULT);
        add(transitions, AgentRunStatus.GENERATING_PATCH,
                AgentRunStatus.VALIDATING_PATCH, AgentRunStatus.PUBLISHING_RESULT);
        add(transitions, AgentRunStatus.VALIDATING_PATCH,
                AgentRunStatus.WAITING_APPROVAL, AgentRunStatus.PUBLISHING_RESULT);
        add(transitions, AgentRunStatus.WAITING_APPROVAL, AgentRunStatus.PUBLISHING_RESULT);
        add(transitions, AgentRunStatus.PUBLISHING_RESULT, AgentRunStatus.COMPLETED);

        Set<AgentRunStatus> retryable = EnumSet.of(
                AgentRunStatus.PREPARING_REPOSITORY,
                AgentRunStatus.ANALYZING_CHANGE,
                AgentRunStatus.PLANNING,
                AgentRunStatus.EXECUTING_TOOLS,
                AgentRunStatus.RETRIEVING_CONTEXT,
                AgentRunStatus.VERIFYING_FINDINGS,
                AgentRunStatus.GENERATING_PATCH,
                AgentRunStatus.VALIDATING_PATCH,
                AgentRunStatus.PUBLISHING_RESULT
        );
        for (AgentRunStatus status : retryable) {
            add(transitions, status,
                    AgentRunStatus.RETRY_WAIT,
                    AgentRunStatus.FAILED,
                    AgentRunStatus.CANCELED,
                    AgentRunStatus.TIMED_OUT);
        }
        add(transitions, AgentRunStatus.RECEIVED,
                AgentRunStatus.FAILED, AgentRunStatus.CANCELED, AgentRunStatus.TIMED_OUT);
        add(transitions, AgentRunStatus.WAITING_APPROVAL,
                AgentRunStatus.CANCELED, AgentRunStatus.TIMED_OUT);
        add(transitions, AgentRunStatus.RETRY_WAIT,
                AgentRunStatus.PREPARING_REPOSITORY,
                AgentRunStatus.ANALYZING_CHANGE,
                AgentRunStatus.PLANNING,
                AgentRunStatus.EXECUTING_TOOLS,
                AgentRunStatus.RETRIEVING_CONTEXT,
                AgentRunStatus.VERIFYING_FINDINGS,
                AgentRunStatus.GENERATING_PATCH,
                AgentRunStatus.VALIDATING_PATCH,
                AgentRunStatus.PUBLISHING_RESULT,
                AgentRunStatus.FAILED,
                AgentRunStatus.CANCELED,
                AgentRunStatus.TIMED_OUT);

        for (AgentRunStatus status : AgentRunStatus.values()) {
            transitions.computeIfAbsent(status, ignored -> Set.of());
        }
        transitions.replaceAll((ignored, allowed) -> Set.copyOf(allowed));
        return Map.copyOf(transitions);
    }

    private static void add(
            EnumMap<AgentRunStatus, Set<AgentRunStatus>> transitions,
            AgentRunStatus from,
            AgentRunStatus... allowed
    ) {
        EnumSet<AgentRunStatus> values = transitions.containsKey(from)
                ? EnumSet.copyOf(transitions.get(from))
                : EnumSet.noneOf(AgentRunStatus.class);
        for (AgentRunStatus status : allowed) {
            values.add(status);
        }
        transitions.put(from, values);
    }
}
