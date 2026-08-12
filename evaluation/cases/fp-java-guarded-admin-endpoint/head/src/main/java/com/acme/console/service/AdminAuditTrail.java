package com.acme.console.service;

/** 管理动作审计落痕。实现层写 admin_action_log 表。 */
public interface AdminAuditTrail {

    void record(Long operatorMemberId, String action, Long targetMemberId);
}
