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

    public String badgeLabel(long userId) {
        Profile profile = profiles.findByUserId(userId).get();
        BadgeTier tier = BadgeTier.forReputation(profile.reputation());
        return profile.displayName() + " · " + tier.label();
    }
}
