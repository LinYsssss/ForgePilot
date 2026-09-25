package com.forgepilot.scm;

import org.springframework.data.jpa.repository.JpaRepository;

/** 关联审计只写不读：它与关联修改同事务写入，追溯时直接查库。 */
interface PullRequestRequirementEventRepository extends JpaRepository<PullRequestRequirementEvent, Long> {
}
