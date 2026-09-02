package com.example.settlement.repository;

import com.example.settlement.model.SettlementRequest;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * 结算请求读写。
 *
 * <p>所有查询都带 tenant_id 过滤，参见 docs/db-schema.md 与 INC-2025-02。
 */
public class SettlementRequestRepository {

    private final DataSource dataSource;

    public SettlementRequestRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private static final String FIND_BY_MERCHANT = """
            select id, tenant_id, merchant_id, idempotency_key,
                   gross_amount, fee_amount, net_amount, currency, status, created_at
              from settlement_request
             where tenant_id = ?
               and merchant_id = ?
               and deleted_at is null
             order by created_at desc
             limit ?
            """;

    private static final String FIND_BY_IDEMPOTENCY_KEY = """
            select id, tenant_id, merchant_id, idempotency_key,
                   gross_amount, fee_amount, net_amount, currency, status, created_at
              from settlement_request
             where tenant_id = ?
               and idempotency_key = ?
            """;

    public List<SettlementRequest> findByMerchant(Long tenantId, Long merchantId, int limit) {
        // 参数绑定，不做字符串拼接
        return JdbcSupport.query(dataSource, FIND_BY_MERCHANT, tenantId, merchantId, limit);
    }

    public Optional<SettlementRequest> findByIdempotencyKey(Long tenantId, String idempotencyKey) {
        return JdbcSupport.queryOne(dataSource, FIND_BY_IDEMPOTENCY_KEY, tenantId, idempotencyKey);
    }
}
