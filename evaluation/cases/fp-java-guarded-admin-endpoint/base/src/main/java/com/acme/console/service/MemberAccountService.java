package com.acme.console.service;

import com.acme.console.model.MemberAccount;
import com.acme.console.repository.MemberAccountRepository;
import com.acme.console.web.OperatorContext;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;

/** 成员账号业务逻辑。数据访问一律以操作者所属租户为范围。 */
@Service
public class MemberAccountService {

    private final MemberAccountRepository repository;

    public MemberAccountService(MemberAccountRepository repository) {
        this.repository = repository;
    }

    public MemberAccount currentProfile(OperatorContext operator) {
        return requireInTenant(operator, operator.getMemberId());
    }

    public MemberAccount renameSelf(OperatorContext operator, String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("显示名不能为空");
        }
        MemberAccount account = requireInTenant(operator, operator.getMemberId());
        account.rename(displayName.trim());
        repository.save(account);
        return account;
    }

    private MemberAccount requireInTenant(OperatorContext operator, Long memberId) {
        return repository.findById(operator.getTenantId(), memberId)
                .orElseThrow(() -> new NoSuchElementException("成员不存在: " + memberId));
    }
}
