package com.acme.payhub.service;

import com.acme.payhub.model.SettlementTicket;
import com.acme.payhub.repository.TicketStore;
import java.util.Optional;

/**
 * 即时结算（T+0）。
 *
 * <p>与 T+1 结算共用费率配置与精度规则：金额全程 long（分），费率从
 * merchant_fee_config 读取，手续费向下取整，最小净额 100 分，仅支持 CNY。
 */
public class InstantTicketService {

    /** 最小结算净额，单位「分」。低于该值不发起，累计到下期。 */
    private static final long MIN_NET_AMOUNT_FEN = 100L;

    private final TicketStore ticketStore;
    private final TicketFeeService feeService;

    public InstantTicketService(TicketStore ticketStore, TicketFeeService feeService) {
        this.ticketStore = ticketStore;
        this.feeService = feeService;
    }

    /**
     * 发起即时结算。幂等键由调用方生成并透传，重试必须复用同一键。
     *
     * @param grossAmountFen 结算总额，单位「分」
     */
    public SettlementTicket submitInstant(Long tenantId, Long merchantId, String idempotencyKey,
                                          long grossAmountFen, String currency) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("缺少幂等键");
        }
        if (!"CNY".equals(currency)) {
            throw new IllegalArgumentException("当前仅支持 CNY 结算");
        }

        Optional<SettlementTicket> existing = ticketStore.findByIdempotencyKey(tenantId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        long feeFen = feeService.calculate(tenantId, merchantId, grossAmountFen);
        long netFen = grossAmountFen - feeFen;
        if (netFen < MIN_NET_AMOUNT_FEN) {
            throw new IllegalStateException("结算净额低于最小值，本期不发起");
        }

        SettlementTicket ticket = new SettlementTicket(
                tenantId, merchantId, idempotencyKey, grossAmountFen, feeFen, currency);
        // 即时结算立即进入发起流程，显式走状态机流转。
        ticket.markProcessing();
        return ticketStore.save(ticket);
    }
}
