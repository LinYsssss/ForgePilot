package com.forgepilot.notification;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 一条通知需要的那几个事实。
 *
 * <p>用朴素 SQL 跨 {@code review}、{@code pull_request}、{@code project} 与 {@code finding}
 * 取数，理由与 {@code ReviewActivityRepository} 一致：跨 feature 不得直接注入对方的
 * {@code *Repository}，而写成 SQL 的连接不产生任何类型依赖。
 *
 * <p>这里<strong>只取计数与标题</strong>，不取 finding 正文、不取 patch 片段。群聊的可见
 * 范围与本系统的权限模型不是一回事：能看到群消息的人不一定是这个项目的成员。
 */
@Repository
class ReviewNotificationRepository {

    private static final String FACTS = """
            select p.name             as project_name,
                   pr.external_number as pr_number,
                   pr.title           as pr_title,
                   rv.requirement_id  as requirement_id, rv.decision,
                   (select u.display_name from user_account u join project_member_role m on m.user_id = u.id
                     where m.project_id = rv.project_id and m.role = 'LEADER') as leader_name,
                   (select u.display_name from user_account u where u.id = req.reviewer_id
                     and exists (select 1 from project_member_role m where m.project_id = rv.project_id
                       and m.user_id = u.id and m.role in ('LEADER', 'REVIEWER'))) as reviewer_name,
                   (select u.display_name from user_account u where u.id = req.assignee_id
                     and exists (select 1 from project_member_role m where m.project_id = rv.project_id
                       and m.user_id = u.id and m.role in ('LEADER', 'DEVELOPER'))) as developer_name,
                   (select count(*) from finding f
                     where f.project_id = rv.project_id and f.review_id = rv.id) as findings,
                   (select count(*) from finding f
                     where f.project_id = rv.project_id and f.review_id = rv.id
                       and f.status = 'OPEN') as open_findings
              from review rv
              join pull_request pr on pr.project_id = rv.project_id and pr.id = rv.pull_request_id
              join project p on p.id = rv.project_id
              left join requirement req on req.project_id = rv.project_id
                   and req.id = rv.requirement_id and req.deleted_at is null
             where rv.project_id = ? and rv.id = ?
            """;

    private final JdbcTemplate jdbc;

    ReviewNotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<ReviewFacts> factsOf(long projectId, long reviewId) {
        return jdbc.query(FACTS,
                (rs, index) -> new ReviewFacts(rs.getString("project_name"),
                        rs.getInt("pr_number"), rs.getString("pr_title"),
                        rs.getObject("requirement_id", Long.class),
                        rs.getInt("findings"), rs.getInt("open_findings"), rs.getString("decision"),
                        rs.getString("leader_name"), rs.getString("reviewer_name"), rs.getString("developer_name")),
                projectId, reviewId).stream().findFirst();
    }

    record ReviewFacts(String projectName, int pullRequestNumber, String pullRequestTitle,
            Long requirementId, int findings, int openFindings, String decision,
            String leaderName, String reviewerName, String developerName) {
    }
}
