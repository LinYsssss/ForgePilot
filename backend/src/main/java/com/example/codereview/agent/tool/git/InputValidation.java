package com.example.codereview.agent.tool.git;

final class InputValidation {

    private InputValidation() {
    }

    static void requireArchive(String value) {
        if (value == null || value.isBlank() || value.length() > 512
                || value.contains("\\") || value.contains("..") || value.startsWith("-")) {
            throw new IllegalArgumentException("archiveRef is invalid");
        }
    }

    static void requireRef(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 200 || value.startsWith("-")
                || value.contains("..") || !value.matches("[A-Za-z0-9][A-Za-z0-9._/-]*")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    static void requireRelativePath(String value) {
        if (value == null || value.isBlank() || value.length() > 512 || value.startsWith("/")
                || value.contains("\\") || value.contains("..") || value.startsWith("-")) {
            throw new IllegalArgumentException("path must be a safe relative path");
        }
    }

    static void requireMaxBytes(int value) {
        if (value <= 0 || value > 65_536) {
            throw new IllegalArgumentException("maxBytes must be 1-65536");
        }
    }
}
