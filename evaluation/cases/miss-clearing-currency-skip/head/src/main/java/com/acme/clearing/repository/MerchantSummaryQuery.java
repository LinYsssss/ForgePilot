package com.acme.clearing.repository;

import java.util.List;
import java.util.Map;

/**
 * 运营后台商户清分汇总查询。
 *
 * <p>安全基线：全部参数经占位符绑定；排序字段走白名单映射；所有查询
 * 强制携带 tenant_id 过滤。
 */
public class MerchantSummaryQuery {

    /** 允许排序的列白名单：外部键 → 实际列名。 */
    private static final Map<String, String> SORTABLE_COLUMNS = Map.of(
            "name", "merchant_name",
            "risk", "risk_level",
            "total", "total_amount");

    private final ParamJdbc jdbc;

    public MerchantSummaryQuery(ParamJdbc jdbc) {
        this.jdbc = jdbc;
    }

    /** 按商户名模糊搜索（供运营后台）。 */
    public List<Map<String, Object>> searchByName(Long tenantId, String keyword, String sortKey) {
        String orderBy = SORTABLE_COLUMNS.get(sortKey);
        if (orderBy == null) {
            throw new IllegalArgumentException("不支持的排序字段: " + sortKey);
        }
        String sql = "select merchant_id, merchant_name, risk_level, total_amount"
                + " from merchant_summary"
                + " where tenant_id = ? and merchant_name like ?"
                + " order by " + orderBy;
        return jdbc.queryList(sql, tenantId, "%" + keyword + "%");
    }

    /** 导出指定商户的清分汇总。 */
    public List<Map<String, Object>> exportSummary(Long tenantId, Long merchantId,
                                                   String from, String to) {
        String sql = "select merchant_id, sum(net_fen) as total, count(*) as cnt"
                + " from clearing_record"
                + " where tenant_id = ? and merchant_id = ?"
                + " and created_at between ? and ?"
                + " group by merchant_id";
        return jdbc.queryList(sql, tenantId, merchantId, from, to);
    }

    /** 占位符绑定的 JDBC 出口。 */
    public interface ParamJdbc {
        List<Map<String, Object>> queryList(String sql, Object... args);
    }
}
