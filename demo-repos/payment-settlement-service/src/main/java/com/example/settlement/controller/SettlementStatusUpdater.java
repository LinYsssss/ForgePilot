package com.example.settlement.controller;

/** 结算状态更新。 */
public interface SettlementStatusUpdater {

    void markSuccess(Long settlementId);

    void markFailed(Long settlementId);
}
