package com.example.codereview.requirement;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.ProjectAuthorization;
import com.example.codereview.member.ProjectRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 需求-代码关联(P3,R4)。自动提取全部走同一有界正则 {@code \bREQ-(\d+)\b};
 * upsert 幂等以唯一约束兜底;REQ 号不存在静默跳过——提取是宿主链路(commit 列表/
 * webhook)的搭车行为,任何失败都不许影响宿主。
 */
@Service
public class RequirementLinkService {

    private static final Logger log = LoggerFactory.getLogger(RequirementLinkService.class);
    /** 有界:REQ 号最长 9 位,防御畸形输入的回溯放大。 */
    private static final Pattern REQ_REF = Pattern.compile("\\bREQ-(\\d{1,9})\\b");
    private static final Set<String> TYPES = Set.of("BRANCH", "COMMIT", "PULL_REQUEST");

    public record LinkResponse(Long linkId, String type, String ref, String source, Instant createdAt) {
    }

    public record LookupResponse(Long requirementId, String code, String title, String status) {
    }

    private final RequirementLinkRepository links;
    private final RequirementRepository requirements;
    private final ProjectAuthorization projectAuthorization;
    // 独立小事务模板:提取是宿主链路(webhook 事务内/无事务的 commit 列表)的搭车行为,
    // 用 REQUIRES_NEW 模板而不是 @Transactional 自调用(代理绕过,database-guidelines 明文坑)。
    private final TransactionTemplate linkTransactions;

    public RequirementLinkService(RequirementLinkRepository links, RequirementRepository requirements,
                                  ProjectAuthorization projectAuthorization,
                                  PlatformTransactionManager transactionManager) {
        this.links = links;
        this.requirements = requirements;
        this.projectAuthorization = projectAuthorization;
        this.linkTransactions = new TransactionTemplate(transactionManager);
        this.linkTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---------------------------------------------------------------- 自动提取(挂点调用,best-effort)

    /** 从文本提取 REQ 号并建 AUTO 关联;宿主链路搭车,异常吞掉只记日志。 */
    public void extractQuietly(Long projectId, String linkType, String ref, String... texts) {
        try {
            LinkedHashSet<Long> seqs = new LinkedHashSet<>();
            for (String text : texts) {
                if (text == null) {
                    continue;
                }
                Matcher matcher = REQ_REF.matcher(text);
                while (matcher.find()) {
                    seqs.add(Long.parseLong(matcher.group(1)));
                }
            }
            for (Long seq : seqs) {
                requirements.findByProjectIdAndSeq(projectId, seq)
                        .ifPresent(requirement -> upsert(projectId, requirement.getId(), linkType, ref, "AUTO"));
            }
        } catch (RuntimeException ex) {
            log.warn("requirement link extraction failed: projectId={}, type={}, ref={}", projectId, linkType, ref, ex);
        }
    }

    /** 分支列表扫描:分支名自身既是提取文本也是 ref。 */
    public void scanBranchesQuietly(Long projectId, List<String> branchNames) {
        for (String branch : branchNames) {
            extractQuietly(projectId, "BRANCH", branch, branch);
        }
    }

    private void upsert(Long projectId, Long requirementId, String linkType, String ref, String source) {
        if (links.findByRequirementIdAndLinkTypeAndRef(requirementId, linkType, ref).isPresent()) {
            return;
        }
        try {
            // 每条链接独立小事务:并发撞唯一约束只废弃这一条的事务,不污染宿主会话。
            linkTransactions.executeWithoutResult(status ->
                    links.saveAndFlush(new RequirementLinkEntity(projectId, requirementId, linkType, ref, source)));
        } catch (DataIntegrityViolationException ignored) {
            // 已存在,幂等目标已达成。
        }
    }

    // ---------------------------------------------------------------- 手动兜底与查询

    public List<LinkResponse> list(Long projectId, Long userId, Long requirementId) {
        projectAuthorization.requireRead(projectId, userId);
        requireRequirement(projectId, requirementId);
        return links.findByRequirementIdOrderByCreatedAtAsc(requirementId).stream()
                .map(link -> new LinkResponse(link.getId(), link.getLinkType(), link.getRef(),
                        link.getSource(), link.getCreatedAt()))
                .toList();
    }

    @Transactional
    public LinkResponse addManual(Long projectId, Long userId, Long requirementId, String type, String ref) {
        projectAuthorization.requireRole(projectId, userId, Set.of(ProjectRole.LEADER, ProjectRole.DEVELOPER));
        requireRequirement(projectId, requirementId);
        String linkType = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(linkType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "关联类型只能是 BRANCH / COMMIT / PULL_REQUEST");
        }
        String trimmedRef = ref == null ? "" : ref.trim();
        if (trimmedRef.isEmpty() || trimmedRef.length() > 512) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "关联引用不能为空且不超过 512 字符");
        }
        if (links.findByRequirementIdAndLinkTypeAndRef(requirementId, linkType, trimmedRef).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该关联已存在");
        }
        RequirementLinkEntity saved = links.save(
                new RequirementLinkEntity(projectId, requirementId, linkType, trimmedRef, "MANUAL"));
        return new LinkResponse(saved.getId(), saved.getLinkType(), saved.getRef(), saved.getSource(),
                saved.getCreatedAt());
    }

    @Transactional
    public void remove(Long projectId, Long userId, Long requirementId, Long linkId) {
        projectAuthorization.requireRole(projectId, userId, Set.of(ProjectRole.LEADER, ProjectRole.DEVELOPER));
        requireRequirement(projectId, requirementId);
        RequirementLinkEntity link = links.findById(linkId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "关联不存在"));
        if (!link.getRequirementId().equals(requirementId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "关联不存在");
        }
        links.delete(link);
    }

    /** 反查(四问入口):这个分支/commit/PR 属于哪些需求。 */
    public List<LookupResponse> lookup(Long projectId, Long userId, String type, String ref) {
        projectAuthorization.requireRead(projectId, userId);
        String linkType = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(linkType) || ref == null || ref.isBlank()) {
            return List.of();
        }
        List<LookupResponse> result = new ArrayList<>();
        for (RequirementLinkEntity link : links.findByProjectIdAndLinkTypeAndRef(projectId, linkType, ref.trim())) {
            requirements.findById(link.getRequirementId()).ifPresent(requirement -> result.add(new LookupResponse(
                    requirement.getId(), "REQ-" + requirement.getSeq(),
                    requirement.getTitle(), requirement.getStatus().name())));
        }
        return result;
    }

    private void requireRequirement(Long projectId, Long requirementId) {
        requirements.findByIdAndProjectId(requirementId, projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REQUIREMENT_NOT_FOUND));
    }
}
