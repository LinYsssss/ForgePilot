package com.acme.console.web;

/** 当前登录操作者，由认证过滤器解析会话后注入控制器方法参数。 */
public class OperatorContext {

    private final Long memberId;
    private final Long tenantId;

    public OperatorContext(Long memberId, Long tenantId) {
        this.memberId = memberId;
        this.tenantId = tenantId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public Long getTenantId() {
        return tenantId;
    }
}
