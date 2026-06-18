package com.example.codereview.git;

import com.example.codereview.common.exception.BusinessException;
import java.util.Locale;
import java.util.regex.Pattern;

public final class GitInputValidator {

    private static final Pattern SAFE_REF = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,200}$");

    private GitInputValidator() {
    }

    public static void requireSafeRef(String value, String field) {
        if (value == null
                || value.isBlank()
                || value.startsWith("-")
                || value.contains("..")
                || !SAFE_REF.matcher(value).matches()) {
            throw new BusinessException(400, "非法的" + field);
        }
    }

    public static void requireSafeRepoUrl(String repoUrl, boolean allowLocalPath) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new BusinessException(400, "仓库地址不能为空");
        }
        String trimmed = repoUrl.trim();
        if (trimmed.startsWith("-") || hasControlChar(trimmed)) {
            throw new BusinessException(400, "非法的仓库地址");
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return;
        }
        if (trimmed.contains("://")) {
            throw new BusinessException(400, allowLocalPath
                    ? "仅支持 http/https 远程仓库地址或本地演示仓库路径"
                    : "仅支持 http/https 远程仓库地址");
        }
        if (trimmed.contains("::")) {
            throw new BusinessException(400, "非法的仓库地址");
        }
        if (isScpLike(trimmed)) {
            throw new BusinessException(400, "暂不支持 SSH 仓库地址，请使用 http/https");
        }
        if (!allowLocalPath) {
            throw new BusinessException(400, "生产环境仅支持 http/https 远程仓库地址");
        }
    }

    private static boolean hasControlChar(String value) {
        return value.chars().anyMatch(c -> c == '\n' || c == '\r' || c == '\t' || c == 0);
    }

    private static boolean isScpLike(String value) {
        boolean windowsDrive = value.length() >= 2
                && Character.isLetter(value.charAt(0))
                && value.charAt(1) == ':';
        if (windowsDrive) {
            return false;
        }
        int colon = value.indexOf(':');
        int slash = value.indexOf('/');
        return colon > 0 && (slash < 0 || colon < slash);
    }
}
