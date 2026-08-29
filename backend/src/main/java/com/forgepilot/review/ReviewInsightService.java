package com.forgepilot.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.requirement.RequirementDirectory;
import com.forgepilot.review.ReviewInsightRepository.Adjudication;
import com.forgepilot.review.ReviewInsightRepository.Criterion;
import com.forgepilot.review.ReviewInsightRepository.LatestReview;
import com.forgepilot.review.ReviewInsightRepository.Unusable;
import com.forgepilot.review.ReviewViews.AcCoverage;
import com.forgepilot.review.ReviewViews.CalibrationBin;
import com.forgepilot.review.ReviewViews.RequirementCoverage;
import com.forgepilot.review.ReviewViews.ReviewCalibration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 两个从既有数据推导出来的只读视图：一条需求当前修订的<strong>覆盖度</strong>，
 * 以及模型自报置信度与人工裁决之间的<strong>校准</strong>。
 *
 * <p>两者都不存储任何东西，每次读取现算——理由与 {@link ReviewActivityService} 相同：
 * 一份存下来的副本必须被每一次审查、每一次人工裁决、每一次修订发布所失效，
 * 而漏掉的那一次就是 UI 无从察觉的谎言。
 *
 * <p>它住在 {@code review} 而不是 {@code requirement}，同样因为依赖箭头是
 * {@code review -> requirement}：让 {@code requirement} 反过来问 {@code review}
 * 会在功能依赖图里闭合出一个环。
 *
 * <p><strong>校准不改变任何行为。</strong>{@code ReviewPrompts} 明确规定
 * {@code confidence} 未经校准、不得参与任何自动门禁或状态流转。本视图只是把
 * 「未经校准」变成「校准到什么程度有据可查」，它<em>度量</em>那个自报值，
 * 不开始<em>信任</em>它。
 */
@Service
@Transactional(readOnly = true)
public class ReviewInsightService {

    private final ReviewInsightRepository insights;
    private final ProjectAccessService access;
    private final RequirementDirectory requirements;

    ReviewInsightService(ReviewInsightRepository insights, ProjectAccessService access,
            RequirementDirectory requirements) {
        this.insights = insights;
        this.access = access;
        this.requirements = requirements;
    }

    /**
     * 一条需求的每条验收条件当前处在什么状态。
     *
     * <p>任何项目成员都能读：它说不出「审查详情」本身没有说过的东西，只是把散落在
     * 多次审查里的结论按验收条件收拢了一遍。
     */
    public RequirementCoverage coverage(long projectId, long actorId, long requirementId) {
        access.requireMember(projectId, actorId);
        // 与其余读路径同款：不属于本项目的 id 与从未签发过的 id 得到相同的 404。
        if (!requirements.existsInProject(projectId, requirementId)) {
            throw ApiException.notFound();
        }

        List<Criterion> criteria = insights.criteriaOfCurrentRevision(projectId, requirementId);
        Optional<LatestReview> latest = insights.latestCompletedReview(projectId, requirementId);

        Map<String, AcVerdict> verdicts = latest
                .map(review -> insights.verdictsOf(projectId, review.reviewId()))
                .orElseGet(Map::of);
        Map<Long, Integer> openFindings = latest
                .map(review -> insights.openFindingsByAc(projectId, review.reviewId()))
                .orElseGet(Map::of);

        List<AcCoverage> rows = new ArrayList<>();
        for (Criterion criterion : criteria) {
            rows.add(new AcCoverage(criterion.acKey(), criterion.text(),
                    verdicts.get(criterion.acKey()),
                    openFindings.getOrDefault(criterion.acId(), 0)));
        }
        return new RequirementCoverage(requirementId,
                latest.map(LatestReview::revisionId).orElse(null),
                latest.map(LatestReview::reviewId).orElse(null), rows);
    }

    /**
     * 模型说自己多有把握，与人实际怎么判，两者对得上吗。
     *
     * <p>三个分箱<strong>总是</strong>全部返回，即使某一档一个样本都没有：一张缺行的表
     * 会被读成「模型从没给过这一档」，而实际含义是「这一档还没有人裁决过」。
     */
    public ReviewCalibration calibration(long projectId, long actorId) {
        access.requireMember(projectId, actorId);

        Map<FindingConfidence, Adjudication> counts = insights.adjudicatedByConfidence(projectId);
        List<CalibrationBin> bins = new ArrayList<>();
        for (FindingConfidence confidence : FindingConfidence.values()) {
            Adjudication adjudication = counts.getOrDefault(confidence, new Adjudication(0, 0));
            long total = adjudication.adjudicated();
            bins.add(new CalibrationBin(confidence, total, adjudication.confirmed(),
                    total == 0 ? null : (double) adjudication.confirmed() / total,
                    Wilson.interval(adjudication.confirmed(), total)));
        }

        Unusable unusable = insights.unusableFindings(projectId);
        return new ReviewCalibration(bins, unusable.awaitingAdjudication(),
                unusable.withoutConfidence());
    }
}
