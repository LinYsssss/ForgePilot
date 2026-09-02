package com.example.settlement.service;
public class MerchantFeePreview {
    public long estimate(long amountFen) { return Math.round(amountFen * 0.006); }
}
