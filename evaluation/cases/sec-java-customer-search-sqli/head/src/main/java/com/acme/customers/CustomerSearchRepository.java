package com.acme.customers;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

public class CustomerSearchRepository {
    private final JdbcTemplate jdbc;

    public CustomerSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> search(String email) {
        return jdbc.queryForList("select id, email from customer where email = '" + email + "'");
    }
}
