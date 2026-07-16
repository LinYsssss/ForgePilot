package com.example.reposage.sandbox;

import java.nio.file.Path;
import java.util.List;

public record DependencyCacheMount(Path source, String target, boolean readOnly, String networkMode) {

    public DependencyCacheMount {
        if (source == null || target == null || !target.startsWith("/") || !"none".equals(networkMode)) {
            throw new IllegalArgumentException("invalid untrusted cache mount");
        }
    }

    public List<String> dockerArguments() {
        String option = "type=bind,src=" + source + ",dst=" + target + (readOnly ? ",readonly" : "");
        return List.of("--mount", option);
    }
}
