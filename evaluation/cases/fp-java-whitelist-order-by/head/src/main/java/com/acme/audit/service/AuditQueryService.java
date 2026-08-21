package com.acme.audit.service;

import com.acme.audit.model.AuditEvent;
import com.acme.audit.repository.AuditEventRepository;
import com.acme.audit.repository.AuditSortField;
import java.sql.SQLException;
import java.util.List;

/** 审计查询入口，供控制台后台页面调用。 */
public class AuditQueryService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final AuditEventRepository repository;

    public AuditQueryService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public List<AuditEvent> recentEvents(long tenantId, int limit) throws SQLException {
        return repository.findRecent(tenantId, clamp(limit));
    }

    /** 关键字搜索；sortRaw 为页面传入的排序字段名，先经白名单解析再进仓储层。 */
    public List<AuditEvent> searchEvents(long tenantId, String keyword, String sortRaw,
                                         boolean ascending, int limit) throws SQLException {
        if (keyword == null || keyword.isBlank()) {
            return recentEvents(tenantId, limit);
        }
        AuditSortField sortField = AuditSortField.fromRequestValue(sortRaw);
        return repository.search(tenantId, keyword.trim(), sortField, ascending, clamp(limit));
    }

    private static int clamp(int limit) {
        if (limit <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }
}
