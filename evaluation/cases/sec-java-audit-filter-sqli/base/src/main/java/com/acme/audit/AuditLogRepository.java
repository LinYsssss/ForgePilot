package com.acme.audit;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

public class AuditLogRepository {
    private final JdbcTemplate jdbc;

    public AuditLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> byActor(String actor) {
        return jdbc.queryForList(
                "select action from audit_log where actor = ? order by created_at desc", actor);
    }
}
