package com.example.codereview.agent.model;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentModelCallAuditService {

    private final AgentModelCallRepository calls;

    public AgentModelCallAuditService(AgentModelCallRepository calls) {
        this.calls = calls;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentModelCall save(AgentModelCall call) {
        return calls.save(call);
    }
}
