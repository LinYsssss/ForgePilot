package com.acme.console.web;

import com.acme.console.model.MemberAccount;
import com.acme.console.service.MemberAccountService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户管理员接口。
 *
 * <p>类级 @PreAuthorize 要求 ADMIN 角色；数据范围仍限操作者所属租户，
 * 跨租户访问由服务层归属校验兜底。
 */
@RestController
@RequestMapping("/api/admin/members")
@PreAuthorize("hasRole('ADMIN')")
public class AdminMemberController {

    private final MemberAccountService service;

    public AdminMemberController(MemberAccountService service) {
        this.service = service;
    }

    @GetMapping
    public List<MemberAccount> list(OperatorContext operator,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return service.listForAdmin(operator, page, size);
    }

    @PostMapping("/{memberId}/disable")
    public MemberAccount disable(OperatorContext operator, @PathVariable Long memberId) {
        return service.disableMember(operator, memberId);
    }
}
