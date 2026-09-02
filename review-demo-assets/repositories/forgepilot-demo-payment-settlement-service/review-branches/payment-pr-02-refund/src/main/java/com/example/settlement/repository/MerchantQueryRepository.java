package com.example.settlement.repository;

import javax.sql.DataSource;

/** 遗留查询入口，尚未收敛到带 tenant_id 的参数化 repository。 */
public class MerchantQueryRepository {
    private final DataSource dataSource;
    public MerchantQueryRepository(DataSource dataSource) { this.dataSource = dataSource; }
    public Object findByMerchant(Long merchantId) {
        return JdbcSupport.query(dataSource, "select * from settlement_request where merchant_id = " + merchantId);
    }
}
