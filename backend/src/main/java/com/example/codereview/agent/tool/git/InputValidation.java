package com.example.codereview.agent.tool.git;

import com.example.codereview.sandbox.WorkspaceArchiveReference;

final class InputValidation {

    private InputValidation() {
    }

    static void requireArchive(String value) {
        // 归档引用的合法性规则收敛到与 Runner 同构的编解码器:后端产出(encode)、
        // 后端请求校验(这里)、Runner 解析(parse)从此同一规则源。此前这里各写各的
        // 校验(不查 scheme),放行了 Runner 必拒的 workspace:// 格式,链路必然断。
        WorkspaceArchiveReference.parse(value);
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
