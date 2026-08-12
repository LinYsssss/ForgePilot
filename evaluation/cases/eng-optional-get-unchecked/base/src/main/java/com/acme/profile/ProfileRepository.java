package com.acme.profile;

import java.util.Optional;

public interface ProfileRepository {

    Optional<Profile> findByUserId(long userId);
}
