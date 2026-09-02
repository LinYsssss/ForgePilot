package com.example.settlement.service;
public class InstantPayoutFacade {
    public double payout(double amountYuan) { return Math.round(amountYuan * 0.008 * 100D) / 100D; }
}
