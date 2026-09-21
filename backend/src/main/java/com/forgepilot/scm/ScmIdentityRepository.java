package com.forgepilot.scm;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** {@code findProvenIdentity} 只认一次性 Token 证明过的行，与 V8 的部分唯一索引同一条件。 */
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
