package com.forgepilot.notification;

import java.util.Optional;

import com.forgepilot.notification.NotificationChannelService.Credentials;
import com.forgepilot.notification.ReviewNotificationRepository.ReviewFacts;
import com.forgepilot.review.ReviewCompleted;
import com.forgepilot.review.ReviewFailed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 一次 Review 跑完之后把摘要推到项目配置的群里。
 *
 * <p>用普通的 {@code @EventListener} 而不是 {@code @TransactionalEventListener}：
 * {@link ReviewCompleted} 与 {@link ReviewFailed} 都是在事务提交<em>之后</em>才发布的，所以终态
 * 这个保证在事件存在的那一刻就已经成立，不需要再叠一层阶段机制。
 *
 * <p><strong>本方法不抛异常</strong>，任何失败都在这里终止。没配渠道、渠道关着、
 * 钉钉不可达、返回非 2xx——全都只留一行日志。这是 {@code package-info} 里那条
 * 「通知是旁路」的落点：一次成功的代码审查不该因为一个聊天机器人而变成失败。
 *
 * <p>它<strong>同步</strong>跑在 review 的 worker 线程上，最多占用它 10 秒
 * （{@code DingTalkSender} 的超时）。没有为它另起线程池：ARCHITECTURE.md 7.1 的取舍
 * 是不引入更多运行时组件，而一次 Review 本身要花上几分钟，10 秒的尾巴不改变吞吐结论。
 */
@Component
class ReviewCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(ReviewCompletedListener.class);

    private final NotificationChannelService channels;
    private final ReviewNotificationRepository facts;
    private final DingTalkSender sender;
    private final String baseUrl;

    ReviewCompletedListener(NotificationChannelService channels, ReviewNotificationRepository facts,
            DingTalkSender sender, @Value("${forgepilot.base-url:}") String baseUrl) {
        this.channels = channels;
        this.facts = facts;
        this.sender = sender;
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
    }

    @EventListener
    void onReviewCompleted(ReviewCompleted event) {
        notify(event.projectId(), event.reviewId(), false);
    }

    @EventListener
    void onReviewFailed(ReviewFailed event) {
        notify(event.projectId(), event.reviewId(), true);
    }

    private void notify(long projectId, long reviewId, boolean failed) {
        try {
            Optional<Credentials> credentials = channels.credentialsOf(projectId);
            if (credentials.isEmpty()) {
                // 记一行而不是静默返回：没有它，「没配渠道」与「发出去了」在日志里长得一模一样，
                // 而这正是排查「消息没到」时最先要排除的分支。
                log.info("Project {} has no enabled notification channel; skipping review {}",
                        projectId, reviewId);
                return;
            }
            Optional<ReviewFacts> found = facts.factsOf(projectId, reviewId);
            if (found.isEmpty()) {
                log.warn("Review {} of project {} vanished before it could be announced",
                        reviewId, projectId);
                return;
            }
            ReviewFacts review = found.get();
            Credentials channel = credentials.get();
            String title = title(channel, review, failed);
            if (!sender.send(channel, title, text(channel, review, reviewId, failed))) {
                log.warn("DingTalk refused the notification for review {} of project {}",
                        reviewId, projectId);
            }
        } catch (RuntimeException failure) {
            // 这里是最后一道拦截。让它逃出去只会污染一个已经成功的 Review 的日志，
            // 而它本身什么也补救不了。
            log.warn("Notification for review {} of project {} failed",
                    reviewId, projectId, failure);
        }
    }

    /**
     * 关键词必须出现在<strong>消息内容</strong>里。机器人若配了「自定义关键词」安全设置，
     * 不含那个词的消息会被钉钉丢弃——而它丢弃时仍然答 HTTP 200，所以少了这一步的表现
     * 是「一切正常但群里什么都没有」。放在最前面，一眼可见。
     */
    private static String title(Credentials channel, ReviewFacts review, boolean failed) {
        String base = (failed ? "代码审查失败：#%d" : "代码审查完成：#%d")
                .formatted(review.pullRequestNumber());
        return channel.hasKeyword() ? channel.keyword() + " " + base : base;
    }

    /**
     * 只放计数与标题。finding 正文与 patch 片段<strong>不进消息</strong>：能看到群消息的人
     * 不一定是这个项目的成员，而这条消息一旦发出就不再受本系统的权限模型约束。
     */
    private String text(Credentials channel, ReviewFacts review, long reviewId, boolean failed) {
        StringBuilder text = new StringBuilder()
                // 标题同样进正文：钉钉按内容匹配关键词，而 markdown 的 title 字段
                // 是否计入匹配没有明确保证，两处都放才稳妥。
                .append("### ").append(title(channel, review, failed)).append("\n\n")
                .append("**项目**：").append(review.projectName()).append("\n\n")
                .append("**PR**：#").append(review.pullRequestNumber())
                .append(' ').append(review.pullRequestTitle()).append("\n\n");
        if (review.requirementId() != null) {
            text.append("**关联需求**：REQ-").append(review.requirementId()).append("\n\n");
        }
        if (failed) {
            text.append("**状态**：审查执行失败，未生成可用报告\n\n");
        } else {
            text.append("**发现**：共 ").append(review.findings())
                    .append(" 条，其中 ").append(review.openFindings()).append(" 条待处理\n\n");
        }
        if (!baseUrl.isEmpty()) {
            text.append("[查看详情](").append(baseUrl).append("/reviews/").append(reviewId).append(")");
        }
        return text.toString();
    }
}
