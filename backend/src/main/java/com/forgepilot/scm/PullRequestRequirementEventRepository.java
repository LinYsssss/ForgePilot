package com.forgepilot.scm;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface PullRequestRequirementEventRepository extends JpaRepository<PullRequestRequirementEvent, Long> {

    List<PullRequestRequirementEvent> findByProjectIdAndPullRequestIdOrderByIdAsc(
            long projectId, long pullRequestId);
}
