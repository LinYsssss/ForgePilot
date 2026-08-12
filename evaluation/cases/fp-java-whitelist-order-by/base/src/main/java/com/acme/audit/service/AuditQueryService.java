package com.acme.audit.service;

import com.acme.audit.model.AuditEvent;
import com.acme.audit.repository.AuditEventRepository;
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

    private static int clamp(int limit) {
        if (limit <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }
}
