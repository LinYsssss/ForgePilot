package com.forgepilot.notification;

import com.forgepilot.notification.ReviewNotificationRepository.ReviewFacts;
import com.forgepilot.review.ReviewCompleted;
import com.forgepilot.review.ReviewFailed;
import com.forgepilot.review.ReviewDecided;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 把三种审查事件变成钉钉群摘要：AI 完成→审查人、AI 失败→LEADER、人工决定→开发者或 LEADER。
 * 尽力而为：任何失败只记日志，绝不影响已提交的业务事实。消息只含处理人与详情链接，
 * 退回理由、Finding 正文与 patch 都不离开受保护的详情页。
 */
@Component
class ReviewCompletedListener {
    private static final Logger log = LoggerFactory.getLogger(ReviewCompletedListener.class);
    private enum Stage { COMPLETED, FAILED, DECIDED }
    private final NotificationChannelService channels;
    private final ReviewNotificationRepository facts;
    private final DingTalkSender sender;
    private final String baseUrl;

    ReviewCompletedListener(NotificationChannelService channels, ReviewNotificationRepository facts,
            DingTalkSender sender, @Value("${forgepilot.base-url:}") String baseUrl) {
        this.channels = channels;
        this.facts = facts;
        this.sender = sender;
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
    }

    // Engine events are already published after commit.
    @EventListener
    void onReviewCompleted(ReviewCompleted event) {
        notify(event.projectId(), event.reviewId(), Stage.COMPLETED);
    }

    @EventListener
    void onReviewFailed(ReviewFailed event) {
        notify(event.projectId(), event.reviewId(), Stage.FAILED);
    }

    @TransactionalEventListener
    void onReviewDecided(ReviewDecided event) {
        notify(event.projectId(), event.reviewId(), Stage.DECIDED);
    }

    private void notify(long projectId, long reviewId, Stage stage) {
        try {
            var credentials = channels.credentialsOf(projectId);
            if (credentials.isEmpty()) {
                log.info("Project {} has no enabled notification channel; skipping review {}", projectId, reviewId);
                return;
            }
            var found = facts.factsOf(projectId, reviewId);
            if (found.isEmpty()) return;
            ReviewFacts review = found.get();
            String status = switch (stage) {
                case COMPLETED -> "AI 审查完成，待人工审查";
                case FAILED -> "AI 审查失败，请检查并重试";
                case DECIDED -> "APPROVE".equals(review.decision()) ? "已通过并合并" : "已退回修改";
            };
            String person = switch (stage) {
                case COMPLETED -> review.reviewerName();
                case FAILED -> review.leaderName();
                case DECIDED -> "REQUEST_CHANGES".equals(review.decision())
                        ? review.developerName() : review.leaderName();
            };
            if (person == null) person = review.leaderName();
            var channel = credentials.get();
            String title = (channel.hasKeyword() ? channel.keyword() + " " : "") + status;
            String text = "### " + title + "\n\n**项目**：" + review.projectName()
                    + "\n\n**PR/MR**：#" + review.pullRequestNumber() + " " + review.pullRequestTitle()
                    + "\n\n**处理人**：" + person + "\n\n";
            if (review.requirementId() != null) text += "**关联需求**：REQ-" + review.requirementId() + "\n\n";
            if (stage == Stage.COMPLETED) text += "**发现**：共 " + review.findings()
                    + " 条，其中 " + review.openFindings() + " 条待处理\n\n";
            if (!baseUrl.isEmpty()) text += "[查看详情](" + baseUrl + "/reviews/" + reviewId
                    + "?project=" + projectId + ")";
            if (!sender.send(channel, title, text)) log.warn("Notification refused for review {}", reviewId);
        } catch (RuntimeException failure) {
            log.warn("Notification for review {} of project {} failed", reviewId, projectId, failure);
        }
    }
}
