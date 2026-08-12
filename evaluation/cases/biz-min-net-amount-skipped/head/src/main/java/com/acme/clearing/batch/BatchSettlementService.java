package com.acme.clearing.batch;

import com.acme.clearing.model.SettlementOrder;
import com.acme.clearing.service.FeeCalculator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 运营后台「一键结算」：把当日全部待结算商户批量落单。
 *
 * <p>结算规则与单笔提交一致，参见 docs/settlement-rules.md。
 */
public class BatchSettlementService {

    private final FeeCalculator feeCalculator;

    public BatchSettlementService(FeeCalculator feeCalculator) {
        this.feeCalculator = feeCalculator;
    }

    /** 批量落单，返回本批生成的结算单。 */
    public List<SettlementOrder> settleAll(Long tenantId, LocalDate settleDate,
                                           List<MerchantBalance> balances) {
        List<SettlementOrder> orders = new ArrayList<>();
        for (MerchantBalance balance : balances) {
            long grossFen = balance.getAvailableFen();
            long feeFen = feeCalculator.calculate(tenantId, balance.getMerchantId(), grossFen);
            String key = "batch-" + settleDate + "-" + balance.getMerchantId();
            orders.add(new SettlementOrder(tenantId, balance.getMerchantId(), key,
                    grossFen, feeFen, "CNY"));
        }
        return orders;
    }
}
