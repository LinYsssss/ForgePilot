package com.example.codereview.agent.tool;

public record ToolResult<O>(
        boolean success,
        O data,
        String error
) {
    public static <O> ToolResult<O> success(O data) {
        return new ToolResult<>(true, data, null);
    }

    public static <O> ToolResult<O> failure(String error) {
        return new ToolResult<>(false, null, error);
    }
}
