package com.acme.profile;

import java.security.Principal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProfileController {
    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @PostMapping("/api/profile/email")
    public void changeEmail(Principal principal, @RequestParam String email) {
        profiles.changeEmail(principal.getName(), email);
    }
}
