package com.example.codereview.scm;

import java.util.List;

public record ScmPublicationResult(boolean success, List<Integer> responseCodes, String message) {

    public ScmPublicationResult {
        responseCodes = responseCodes == null ? List.of() : List.copyOf(responseCodes);
    }
}
