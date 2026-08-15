package com.example.codereview.scm;

import java.util.List;

/**
 * 与 provider 无关的 Agent 审查结果,交给对应适配器渲染成 GitHub Check / PR 评论,
 * 或 GitLab MR note / commit status。
 *
 * <p>它带着摘要、阻断级问题、证据链接、Agent Run URL,以及补丁校验状态。
 * {@link #exposesPatchContent()} 标记那些**会泄露生成补丁内容**的发布——这类发布必须先拿到
 * 显式审批才能发出(见 Task 11)。findings 与 evidence 列表都做了防御性拷贝,且永不为 null。
 */
public record ReviewPublication(
        Conclusion conclusion,
        String summary,
        List<String> blockingFindings,
        List<String> evidenceLinks,
        String agentRunUrl,
        PatchValidationState patchValidationState,
        boolean exposesPatchContent
) {
    /** 高层结论,按 provider 分别映射到 check conclusion / MR 状态。 */
    public enum Conclusion {
        SUCCESS,
        ACTION_REQUIRED,
        NEUTRAL
    }

    /** 生成补丁的校验状态,会呈现给评审者。 */
    public enum PatchValidationState {
        NOT_APPLICABLE,
        PENDING,
        VALIDATED,
        FAILED,
        PENDING_APPROVAL
    }

    public ReviewPublication {
        if (conclusion == null) {
            throw new IllegalArgumentException("conclusion is required");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary is required");
        }
        if (agentRunUrl == null || agentRunUrl.isBlank()) {
            throw new IllegalArgumentException("agentRunUrl is required");
        }
        patchValidationState = patchValidationState == null ? PatchValidationState.NOT_APPLICABLE : patchValidationState;
        blockingFindings = blockingFindings == null ? List.of() : List.copyOf(blockingFindings);
        evidenceLinks = evidenceLinks == null ? List.of() : List.copyOf(evidenceLinks);
    }
}
