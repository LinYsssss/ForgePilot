package com.acme.audit.repository;

import com.acme.audit.model.AuditEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * 审计事件查询。
 *
 * <p>所有查询强制携带 tenant_id 过滤；外部输入一律走参数绑定。
 */
public class AuditEventRepository {

    private static final String SELECT_COLUMNS = """
            select id, tenant_id, action, operator, detail, created_at
              from audit_event
            """;

    private final DataSource dataSource;

    public AuditEventRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public List<AuditEvent> findRecent(long tenantId, int limit) throws SQLException {
        String sql = SELECT_COLUMNS + " where tenant_id = ? order by created_at desc limit ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, tenantId);
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                return mapRows(rs);
            }
        }
    }

    /**
     * 关键字搜索 + 可选排序。
     *
     * <p>ORDER BY 片段仅由 {@link AuditSortField} 白名单常量与二选一的方向词拼成；
     * 关键字始终经占位符绑定，LIKE 通配符拼在参数值上而非 SQL 文本上。
     */
    public List<AuditEvent> search(long tenantId, String keyword, AuditSortField sortField,
                                   boolean ascending, int limit) throws SQLException {
        String sql = SELECT_COLUMNS
                + " where tenant_id = ? and (action like ? escape '\\' or operator like ? escape '\\')"
                + " order by " + sortField.column() + (ascending ? " asc" : " desc")
                + " limit ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, tenantId);
            String pattern = "%" + escapeLike(keyword) + "%";
            statement.setString(2, pattern);
            statement.setString(3, pattern);
            statement.setInt(4, limit);
            try (ResultSet rs = statement.executeQuery()) {
                return mapRows(rs);
            }
        }
    }

    /** 转义 LIKE 通配符，防止关键字里的 % 与 _ 扩大匹配范围。 */
    private static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private List<AuditEvent> mapRows(ResultSet rs) throws SQLException {
        List<AuditEvent> events = new ArrayList<>();
        while (rs.next()) {
            events.add(new AuditEvent(
                    rs.getLong("id"),
                    rs.getLong("tenant_id"),
                    rs.getString("action"),
                    rs.getString("operator"),
                    rs.getString("detail"),
                    rs.getTimestamp("created_at").toInstant()));
        }
        return events;
    }
}
