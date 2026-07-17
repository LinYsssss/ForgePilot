package com.example.codereview.patch;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatchApprovalRepository extends JpaRepository<PatchApproval, Long> {
    Optional<PatchApproval> findByPatchCandidateIdAndApproverId(Long patchCandidateId, Long approverId);
}
