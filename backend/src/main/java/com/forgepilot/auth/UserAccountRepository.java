package com.forgepilot.auth;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUsername(String username);

    @Query("""
            select a from UserAccount a
            where lower(a.username) like lower(concat('%', :query, '%'))
               or lower(a.displayName) like lower(concat('%', :query, '%'))
               or (:exactId is not null and a.id = :exactId)
            order by a.displayName, a.username, a.id
            """)
    Page<UserAccount> search(@Param("query") String query, @Param("exactId") Long exactId, Pageable pageable);
}
