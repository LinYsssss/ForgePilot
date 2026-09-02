package com.example.settlement.repository;

import com.example.settlement.model.SettlementRequest;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** 极简 JDBC 辅助，演示仓库用，不追求完整实现。 */
final class JdbcSupport {

    private JdbcSupport() {
    }

    static List<SettlementRequest> query(DataSource dataSource, String sql, Object... params) {
        throw new UnsupportedOperationException("demo repository");
    }

    static Optional<SettlementRequest> queryOne(DataSource dataSource, String sql, Object... params) {
        throw new UnsupportedOperationException("demo repository");
    }

    static int update(DataSource dataSource, String sql, Object... params) {
        throw new UnsupportedOperationException("demo repository");
    }
}
