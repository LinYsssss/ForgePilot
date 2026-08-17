package com.example.codereview.context;

import com.example.codereview.ai.PromptSanitizer;
import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.ProjectAuthorization;
import com.example.codereview.git.GitCliService;
import com.example.codereview.knowledge.KnowledgeDtos.SearchMatch;
import com.example.codereview.pullrequest.PullRequestEntity;
import com.example.codereview.pullrequest.PullRequestRepository;
import com.example.codereview.rag.RagService;
import com.example.codereview.repo.CodeRepositoryEntity;
import com.example.codereview.repo.CodeRepositoryJpaRepository;
import com.example.codereview.repo.RepositoryDtos.DiffFileResponse;
import com.example.codereview.requirement.AcceptanceCriterionEntity;
import com.example.codereview.requirement.AcceptanceCriterionRepository;
import com.example.codereview.requirement.RequirementEntity;
import com.example.codereview.requirement.RequirementLinkEntity;
import com.example.codereview.requirement.RequirementLinkRepository;
import com.example.codereview.requirement.RequirementRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Unified, budgeted context builder. Optional assistant evidence degrades without hiding failures. */
@Component
public class ContextBuilder {

    private final RagService ragService;
    private final ProjectAuthorization projectAuthorization;
    private final RequirementRepository requirements;
    private final AcceptanceCriterionRepository criteria;
    private final RequirementLinkRepository links;
    private final CodeRepositoryJpaRepository repositories;
    private final PullRequestRepository pullRequests;
    private final GitCliService gitCli;
    private final int knowledgeTopK;
    private final int maxSnippetChars;
    private final int maxQueryChars;
    private final int maxCodeLinks;
    private final int maxCodeFiles;
    private final int maxCodeFileChars;
    private final int maxCodeTotalChars;

    public ContextBuilder(RagService ragService,
                          ProjectAuthorization projectAuthorization,
                          RequirementRepository requirements,
                          AcceptanceCriterionRepository criteria,
                          RequirementLinkRepository links,
                          CodeRepositoryJpaRepository repositories,
                          PullRequestRepository pullRequests,
                          GitCliService gitCli,
                          @Value("${app.context.knowledge-top-k:5}") int knowledgeTopK,
                          @Value("${app.context.max-snippet-chars:1200}") int maxSnippetChars,
                          @Value("${app.assistant.context.max-query-chars:4000}") int maxQueryChars,
                          @Value("${app.assistant.context.max-code-links:4}") int maxCodeLinks,
                          @Value("${app.assistant.context.max-code-files:12}") int maxCodeFiles,
                          @Value("${app.assistant.context.max-code-file-chars:3000}") int maxCodeFileChars,
                          @Value("${app.assistant.context.max-code-total-chars:12000}") int maxCodeTotalChars) {
        this.ragService = ragService;
        this.projectAuthorization = projectAuthorization;
        this.requirements = requirements;
        this.criteria = criteria;
        this.links = links;
        this.repositories = repositories;
        this.pullRequests = pullRequests;
        this.gitCli = gitCli;
        this.knowledgeTopK = Math.max(1, knowledgeTopK);
        this.maxSnippetChars = Math.max(0, maxSnippetChars);
        this.maxQueryChars = Math.max(1, Math.min(100000, maxQueryChars));
        this.maxCodeLinks = Math.max(0, maxCodeLinks);
        this.maxCodeFiles = Math.max(0, maxCodeFiles);
        this.maxCodeFileChars = Math.max(0, maxCodeFileChars);
        this.maxCodeTotalChars = Math.max(0, maxCodeTotalChars);
    }

    /** Old one-argument construction remains source-compatible with REQUIREMENT_CHECK. */
    public record Refs(String query, Long userId, Long requirementId) {
        public Refs(String query) {
            this(query, null, null);
        }

        public static Refs assistant(Long userId, Long requirementId) {
            return new Refs("", userId, requirementId);
        }
    }

    public record KnowledgeSnippet(String sourceId, String sourceName, String content) {
        public KnowledgeSnippet(String sourceName, String content) {
            this("KB:" + safeLabel(sourceName), sourceName, content);
        }
    }

    public record RequirementSnapshot(String sourceId, String code, String title, String background,
                                      String description, String status, List<AcceptanceCriterion> acceptanceCriteria) {
    }

    public record AcceptanceCriterion(String sourceId, int seq, String text) {
    }

    public record CodeSlice(String sourceId, String linkType, String ref, String filePath,
                            String changeType, String diff) {
    }

    public record Source(String id, String type, String title, String ref) {
    }

    public record ContextBundle(List<KnowledgeSnippet> knowledgeSnippets, int truncatedSnippets,
                                RequirementSnapshot requirement, List<CodeSlice> codeSlices,
                                List<Source> sources, List<String> truncatedSections, List<String> warnings) {
        public ContextBundle(List<KnowledgeSnippet> knowledgeSnippets, int truncatedSnippets) {
            this(knowledgeSnippets, truncatedSnippets, null, List.of(), List.of(), List.of(), List.of());
        }
    }

    public ContextBundle build(ContextScene scene, Long projectId, Refs refs) {
        return switch (scene) {
            case REQUIREMENT_CHECK -> buildRequirementCheck(projectId, refs);
            case ASSISTANT -> buildAssistant(projectId, refs);
            default -> throw new UnsupportedOperationException("scene " + scene + " 尚未接入 ContextBuilder");
        };
    }

    private ContextBundle buildRequirementCheck(Long projectId, Refs refs) {
        String query = refs == null || refs.query() == null ? "" : refs.query().strip();
        if (query.isEmpty()) {
            return new ContextBundle(List.of(), 0);
        }
        KnowledgeResult knowledge = retrieveKnowledge(projectId, query, false, new ArrayList<>());
        return new ContextBundle(knowledge.snippets(), knowledge.truncated());
    }

    private ContextBundle buildAssistant(Long projectId, Refs refs) {
        if (refs == null || refs.userId() == null || refs.requirementId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "assistant context requires userId and requirementId");
        }
        projectAuthorization.requireRead(projectId, refs.userId());
        RequirementEntity requirement = requirements.findByIdAndProjectId(refs.requirementId(), projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REQUIREMENT_NOT_FOUND));
        List<AcceptanceCriterionEntity> acEntities = criteria.findByRequirementIdOrderBySeqAsc(requirement.getId());
        List<AcceptanceCriterion> acs = acEntities.stream()
                .map(ac -> new AcceptanceCriterion("AC-" + ac.getSeq(), ac.getSeq(), ac.getText()))
                .toList();
        RequirementSnapshot snapshot = new RequirementSnapshot(
                "REQ-" + requirement.getSeq(), "REQ-" + requirement.getSeq(), requirement.getTitle(),
                safe(requirement.getBackground()), safe(requirement.getDescription()),
                requirement.getStatus().name(), acs);

        List<Source> sources = new ArrayList<>();
        sources.add(new Source(snapshot.sourceId(), "REQUIREMENT", snapshot.title(), snapshot.code()));
        for (AcceptanceCriterion ac : acs) {
            sources.add(new Source(ac.sourceId(), "AC", "验收标准 " + ac.seq(), ac.sourceId()));
        }
        List<String> warnings = new ArrayList<>();
        List<String> truncatedSections = new ArrayList<>();
        String query = PromptSanitizer.truncate(
                PromptSanitizer.redact(assistantQuery(requirement, acEntities)),
                maxQueryChars >= Integer.MAX_VALUE / 4 ? Integer.MAX_VALUE : maxQueryChars * 4,
                maxQueryChars);
        KnowledgeResult knowledge = retrieveKnowledge(projectId, query, true, warnings);
        if (knowledge.truncated() > 0) {
            truncatedSections.add("knowledge");
        }
        for (KnowledgeSnippet snippet : knowledge.snippets()) {
            sources.add(new Source(snippet.sourceId(), "KNOWLEDGE", snippet.sourceName(), snippet.sourceName()));
        }

        List<CodeSlice> codeSlices = new ArrayList<>();
        collectCode(projectId, requirement.getId(), codeSlices, sources, warnings, truncatedSections);
        return new ContextBundle(knowledge.snippets(), knowledge.truncated(), snapshot,
                List.copyOf(codeSlices), List.copyOf(sources), List.copyOf(truncatedSections), List.copyOf(warnings));
    }

    private KnowledgeResult retrieveKnowledge(Long projectId, String query, boolean optional, List<String> warnings) {
        try {
            List<SearchMatch> matches = ragService.search(projectId, query, knowledgeTopK);
            int truncated = 0;
            List<KnowledgeSnippet> snippets = new ArrayList<>();
            for (SearchMatch match : matches) {
                String content = safe(match.content());
                if (content.length() > maxSnippetChars) {
                    content = content.substring(0, maxSnippetChars);
                    truncated++;
                }
                String id = match.chunkId() == null
                        ? "KB:" + safeLabel(match.sourceName()) + ":" + match.chunkIndex()
                        : "KB:" + match.chunkId();
                snippets.add(new KnowledgeSnippet(id, match.sourceName(), content));
            }
            return new KnowledgeResult(List.copyOf(snippets), truncated);
        } catch (RuntimeException ex) {
            if (!optional) {
                throw ex;
            }
            warnings.add("知识库检索暂不可用，本轮仅使用需求与可用代码上下文。");
            return new KnowledgeResult(List.of(), 0);
        }
    }

    private void collectCode(Long projectId, Long requirementId, List<CodeSlice> slices, List<Source> sources,
                             List<String> warnings, List<String> truncatedSections) {
        List<RequirementLinkEntity> linked;
        try {
            linked = links.findByRequirementIdOrderByCreatedAtAsc(requirementId);
        } catch (RuntimeException ex) {
            warnings.add("代码关联暂不可用，本轮仅使用需求与知识库上下文。");
            return;
        }
        if (linked.isEmpty()) {
            return;
        }
        Optional<CodeRepositoryEntity> repository;
        try {
            repository = repositories.findByProjectId(projectId);
        } catch (RuntimeException ex) {
            repository = Optional.empty();
            warnings.add("项目仓库信息暂不可用，代码关联仅作为引用元数据展示。");
        }
        int totalChars = 0;
        int files = 0;
        int linkIndex = 0;
        for (RequirementLinkEntity link : linked) {
            if (linkIndex >= maxCodeLinks) {
                addOnce(truncatedSections, "code_links");
                break;
            }
            linkIndex++;
            String type = safe(link.getLinkType()).toUpperCase(Locale.ROOT);
            String linkKey = link.getId() == null ? String.valueOf(linkIndex) : String.valueOf(link.getId());
            String metadataId = "CODE:" + type + ":" + linkKey;
            sources.add(new Source(metadataId, "CODE_LINK", type + " " + safe(link.getRef()), safe(link.getRef())));
            if ("BRANCH".equals(type)) {
                continue;
            }
            if (repository.isEmpty()) {
                addOnce(warnings, "项目尚未绑定仓库，代码关联仅作为引用元数据展示。");
                continue;
            }
            try {
                DiffRef diffRef = resolveDiff(projectId, type, link.getRef());
                if (diffRef == null) {
                    warnings.add(type + " 引用无法解析：" + link.getRef());
                    continue;
                }
                List<DiffFileResponse> diffFiles = gitCli.diff(repository.get(), diffRef.head(), diffRef.base()).files();
                int fileInLink = 0;
                for (DiffFileResponse file : diffFiles) {
                    if (files >= maxCodeFiles || totalChars >= maxCodeTotalChars) {
                        addOnce(truncatedSections, "code");
                        break;
                    }
                    int remaining = maxCodeTotalChars - totalChars;
                    int limit = Math.min(maxCodeFileChars, remaining);
                    String diff = safe(file.diff());
                    String bounded = diff.length() <= limit ? diff : diff.substring(0, limit);
                    if (!bounded.equals(diff)) {
                        addOnce(truncatedSections, "code");
                    }
                    fileInLink++;
                    String sourceId = metadataId + ":" + fileInLink;
                    slices.add(new CodeSlice(sourceId, type, link.getRef(), file.filePath(), file.changeType(), bounded));
                    sources.add(new Source(sourceId, "CODE", file.filePath(), link.getRef()));
                    totalChars += bounded.length();
                    files++;
                }
            } catch (RuntimeException ex) {
                warnings.add(type + " 代码上下文读取失败：" + link.getRef());
            }
        }
    }

    private DiffRef resolveDiff(Long projectId, String type, String ref) {
        if ("COMMIT".equals(type)) {
            return new DiffRef(ref, null);
        }
        if (!"PULL_REQUEST".equals(type)) {
            return null;
        }
        String normalized = ref == null ? "" : ref.strip().replaceFirst("^#", "");
        Optional<PullRequestEntity> pr = normalized.matches("\\d+")
                ? pullRequests.findByProjectIdAndPrNumber(projectId, Integer.parseInt(normalized))
                : pullRequests.findByProjectIdAndExternalPrId(projectId, normalized);
        return pr.map(value -> new DiffRef(value.getHeadSha(), value.getBaseSha())).orElse(null);
    }

    private String assistantQuery(RequirementEntity requirement, List<AcceptanceCriterionEntity> acs) {
        StringBuilder query = new StringBuilder(requirement.getTitle()).append('\n')
                .append(safe(requirement.getBackground())).append('\n')
                .append(safe(requirement.getDescription()));
        for (AcceptanceCriterionEntity ac : acs) {
            query.append('\n').append(ac.getText());
        }
        return query.toString();
    }

    private static void addOnce(List<String> target, String value) {
        if (!target.contains(value)) {
            target.add(value);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safeLabel(String value) {
        String normalized = safe(value).strip().replaceAll("[^A-Za-z0-9._/-]+", "-");
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private record KnowledgeResult(List<KnowledgeSnippet> snippets, int truncated) {
    }

    private record DiffRef(String head, String base) {
    }
}
