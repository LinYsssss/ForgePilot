package com.acme.audit.repository;

import java.util.Locale;

/**
 * 审计查询允许的排序字段白名单。
 *
 * <p>ORDER BY 的列名无法使用占位符，只能拼接进 SQL 文本；因此列名绝不取自请求原文，
 * 只允许取自本枚举携带的常量，请求串仅用于在白名单内查找对应枚举项。
 */
public enum AuditSortField {
    CREATED_AT("created_at"),
    ACTION("action"),
    OPERATOR("operator");

    private final String column;

    AuditSortField(String column) {
        this.column = column;
    }

    /** 返回白名单内的物理列名，仅供拼入 ORDER BY 片段。 */
    public String column() {
        return column;
    }

    /** 从请求参数解析排序字段；未匹配到白名单时回落到 CREATED_AT。 */
    public static AuditSortField fromRequestValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return CREATED_AT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return CREATED_AT;
        }
    }
}
