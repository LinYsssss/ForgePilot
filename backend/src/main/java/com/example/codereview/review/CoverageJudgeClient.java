package com.example.codereview.review;

import com.example.codereview.review.CoverageDtos.CoverageInput;
import com.example.codereview.review.CoverageDtos.CoverageResult;

/**
 * AC 覆盖判定的 LLM 客户端(P4a)。合并阶段单独一次调用(design §5):
 * 输入 = 需求 + AC + 分片结论摘要 + 有界 diff 片段;mock 与真模型共用解析校验。
 */
public interface CoverageJudgeClient {

    CoverageResult judge(CoverageInput input, String shardSummaries, String diffExcerpt);
}
