package com.acme.console.service;

import com.acme.console.model.MemberAccount;
import com.acme.console.repository.MemberAccountRepository;
import com.acme.console.web.OperatorContext;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** 成员账号业务逻辑。数据访问一律以操作者所属租户为范围。 */
@Service
public class MemberAccountService {

    private static final int MAX_PAGE_SIZE = 100;

    private final MemberAccountRepository repository;
    private final AdminAuditTrail auditTrail;

    public MemberAccountService(MemberAccountRepository repository, AdminAuditTrail auditTrail) {
        this.repository = repository;
        this.auditTrail = auditTrail;
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

    /** 管理员分页查看本租户成员；分页参数在服务端夹紧，数据范围固定为操作者租户。 */
    public List<MemberAccount> listForAdmin(OperatorContext operator, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        return repository.findByTenant(operator.getTenantId(), safePage * safeSize, safeSize);
    }

    /** 管理员停用本租户成员；跨租户目标一律拒绝，且不允许停用当前登录账号。 */
    public MemberAccount disableMember(OperatorContext operator, Long memberId) {
        if (memberId.equals(operator.getMemberId())) {
            throw new IllegalArgumentException("不能停用当前登录账号");
        }
        MemberAccount target = requireInTenant(operator, memberId);
        // findById 已按操作者租户过滤，此处再显式比对一次作为双保险。
        if (!operator.getTenantId().equals(target.getTenantId())) {
            throw new AccessDeniedException("禁止操作其他租户的成员");
        }
        target.deactivate();
        repository.save(target);
        auditTrail.record(operator.getMemberId(), "MEMBER_DISABLE", memberId);
        return target;
    }

    private MemberAccount requireInTenant(OperatorContext operator, Long memberId) {
        return repository.findById(operator.getTenantId(), memberId)
                .orElseThrow(() -> new NoSuchElementException("成员不存在: " + memberId));
    }
}
