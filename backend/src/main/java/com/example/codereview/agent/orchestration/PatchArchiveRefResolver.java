package com.example.codereview.agent.orchestration;

import com.example.codereview.patch.PatchCandidate;
import org.springframework.stereotype.Component;

@Component
public class PatchArchiveRefResolver {

    public String resolve(PatchCandidate patch) {
        if (patch == null || patch.getId() == null || patch.getId() <= 0) {
            throw new IllegalArgumentException("persisted patch candidate is required");
        }
        return "workspace://patch-" + patch.getId() + "-" + patch.getPatchHash() + ".tar";
    }
}
