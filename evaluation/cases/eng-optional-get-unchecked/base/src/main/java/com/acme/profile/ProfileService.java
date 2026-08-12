package com.acme.profile;

import org.springframework.stereotype.Service;

@Service
public class ProfileService {

    private final ProfileRepository profiles;

    public ProfileService(ProfileRepository profiles) {
        this.profiles = profiles;
    }

    public String displayName(long userId) {
        return profiles.findByUserId(userId)
                .map(Profile::displayName)
                .orElse("anonymous");
    }
}
