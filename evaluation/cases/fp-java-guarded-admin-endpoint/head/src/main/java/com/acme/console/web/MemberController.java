package com.acme.console.web;

import com.acme.console.model.MemberAccount;
import com.acme.console.service.MemberAccountService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 成员自助接口：只操作本人账号。 */
@RestController
@RequestMapping("/api/members/me")
public class MemberController {

    private final MemberAccountService service;

    public MemberController(MemberAccountService service) {
        this.service = service;
    }

    @GetMapping
    public MemberAccount profile(OperatorContext operator) {
        return service.currentProfile(operator);
    }

    @PatchMapping("/display-name")
    public MemberAccount rename(OperatorContext operator, @RequestBody RenamePayload payload) {
        return service.renameSelf(operator, payload.displayName());
    }

    public record RenamePayload(String displayName) {}
}
