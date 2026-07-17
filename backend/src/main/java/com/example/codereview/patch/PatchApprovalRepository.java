package com.example.codereview.patch;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatchApprovalRepository extends JpaRepository<PatchApproval, Long> {
    Optional<PatchApproval> findByPatchCandidateIdAndApproverId(Long patchCandidateId, Long approverId);
    List<PatchApproval> findByPatchCandidateIdOrderByDecidedAtDesc(Long patchCandidateId);
}
