package com.example.settlement.repository;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/** 直接执行 SQL 字符串，演示仓库用。 */
final class RawJdbc {

    private RawJdbc() {
    }

    static List<Map<String, Object>> queryList(DataSource dataSource, String sql) {
        throw new UnsupportedOperationException("demo");
    }
}
