package com.example.codereview.scm;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScmInstallationRepository extends JpaRepository<ScmInstallation, Long> {

    Optional<ScmInstallation> findByProviderAndExternalInstallationId(
            ScmProviderType provider, String externalInstallationId);

    Optional<ScmInstallation> findByProviderAndExternalInstallationIdAndActiveTrue(
            ScmProviderType provider, String externalInstallationId);
}
