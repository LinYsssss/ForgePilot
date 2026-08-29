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
                   rv.requirement_id  as requirement_id,
                   (select count(*) from finding f
                     where f.project_id = rv.project_id and f.review_id = rv.id) as findings,
                   (select count(*) from finding f
                     where f.project_id = rv.project_id and f.review_id = rv.id
                       and f.status = 'OPEN') as open_findings
              from review rv
              join pull_request pr on pr.project_id = rv.project_id and pr.id = rv.pull_request_id
              join project p on p.id = rv.project_id
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
                        rs.getInt("findings"), rs.getInt("open_findings")),
                projectId, reviewId).stream().findFirst();
    }

    record ReviewFacts(String projectName, int pullRequestNumber, String pullRequestTitle,
            Long requirementId, int findings, int openFindings) {
    }
}
