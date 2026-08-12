package com.acme.console.repository;

import com.acme.console.model.MemberAccount;
import java.util.List;
import java.util.Optional;

/** 成员账号读写。实现层所有查询强制携带 tenant_id 条件。 */
public interface MemberAccountRepository {

    Optional<MemberAccount> findById(Long tenantId, Long memberId);

    List<MemberAccount> findByTenant(Long tenantId, int offset, int limit);

    void save(MemberAccount account);
}
