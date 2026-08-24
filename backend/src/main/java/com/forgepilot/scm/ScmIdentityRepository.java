package com.forgepilot.scm;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ScmIdentityRepository extends JpaRepository<ScmIdentity, Long> {
    List<ScmIdentity> findByUserIdOrderByIdAsc(long userId);
    Optional<ScmIdentity> findByUserIdAndId(long userId, long id);
    @Query("""
            select i from ScmIdentity i
            where i.provider = :provider and i.instanceIdentity = :instanceIdentity
              and i.externalUserId = :externalUserId
              and i.verificationMethod = com.forgepilot.scm.ScmIdentity$VerificationMethod.ONE_TIME_TOKEN
            """)
    Optional<ScmIdentity> findProvenIdentity(ScmProvider provider, String instanceIdentity,
            String externalUserId);
}
