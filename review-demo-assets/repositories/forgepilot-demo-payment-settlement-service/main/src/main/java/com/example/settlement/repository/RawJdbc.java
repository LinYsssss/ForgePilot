package com.example.settlement.repository;

import javax.sql.DataSource;

/** 遗留 JDBC 入口，供审查整改需求演示。 */
public class RawJdbc {
    private final DataSource dataSource;
    public RawJdbc(DataSource dataSource) { this.dataSource = dataSource; }
    public int execute(String sql) { return JdbcSupport.update(dataSource, sql); }
}
