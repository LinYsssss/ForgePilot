package com.example.codereview.requirement;

import com.example.codereview.requirement.RequirementCheckDtos.LlmCheckResult;

/**
 * 体检 LLM 层客户端(P2)。与 {@code AiReviewClient} 同一套 provider 开关:
 * mock 实现零依赖可离线演示,openai-compatible 实现与真实模型对话;
 * 两条路径共用同一解析/校验(schema 不合格整体拒绝,不落库)。
 */
public interface RequirementCheckClient {

    LlmCheckResult analyze(String requirementBlock, String knowledgeBlock);
}
