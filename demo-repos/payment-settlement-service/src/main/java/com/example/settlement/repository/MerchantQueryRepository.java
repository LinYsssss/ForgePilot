package com.example.settlement.repository;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * 运营后台商户查询。
 */
public class MerchantQueryRepository {

    private final DataSource dataSource;

    public MerchantQueryRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 按商户名模糊搜索，供运营后台使用。
     */
    public List<Map<String, Object>> searchByName(String keyword, String orderBy) {
        String sql = "select merchant_id, merchant_name, risk_level, total_amount"
                + " from merchant_summary"
                + " where merchant_name like '%" + keyword + "%'"
                + " order by " + orderBy;
        return RawJdbc.queryList(dataSource, sql);
    }

    /**
     * 导出指定商户的结算汇总。
     */
    public List<Map<String, Object>> exportSettlementSummary(Long merchantId, String from, String to) {
        String sql = "select merchant_id, sum(net_amount) as total, count(*) as cnt"
                + " from settlement_request"
                + " where merchant_id = " + merchantId
                + " and created_at between '" + from + "' and '" + to + "'"
                + " group by merchant_id";
        return RawJdbc.queryList(dataSource, sql);
    }
}
