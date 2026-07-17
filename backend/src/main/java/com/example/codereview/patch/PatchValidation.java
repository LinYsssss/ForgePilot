package com.example.codereview.patch;

import java.util.List;

public record PatchValidation(boolean valid, String reason, List<String> files, int changedLines) {
    public PatchValidation {
        files = files == null ? List.of() : List.copyOf(files);
    }

    public static PatchValidation rejected(String reason) {
        return new PatchValidation(false, reason, List.of(), 0);
    }
}
