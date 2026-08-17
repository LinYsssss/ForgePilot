package com.example.codereview.requirement;

import com.example.codereview.requirement.RequirementCheckDtos.CheckDimension;
import com.example.codereview.requirement.RequirementCheckDtos.CheckItem;
import com.example.codereview.requirement.RequirementCheckDtos.DimensionReport;
import com.example.codereview.requirement.RequirementCheckDtos.LlmCheckResult;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 体检 LLM 层的 mock 实现(P2):确定性启发,零外部依赖,供离线演示与测试。
 * 与规则层互补——这里模拟的是"语义层"结论(异常场景遗漏、知识冲突提示)。
 */
@Service
@ConditionalOnProperty(prefix = "app.ai", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockRequirementCheckClient implements RequirementCheckClient {

    private static final String[] EXCEPTION_HINTS = {"异常", "超时", "并发", "失败", "重复", "回滚", "降级", "幂等"};

    @Override
    public LlmCheckResult analyze(String requirementBlock, String knowledgeBlock) {
        String lower = (requirementBlock == null ? "" : requirementBlock).toLowerCase(Locale.ROOT);
        Map<CheckDimension, List<CheckItem>> byDimension = new EnumMap<>(CheckDimension.class);
        for (CheckDimension dimension : CheckDimension.values()) {
            byDimension.put(dimension, new ArrayList<>());
        }
        if (!containsAny(lower, EXCEPTION_HINTS)) {
            byDimension.get(CheckDimension.EXCEPTION_COVERAGE).add(new CheckItem(
                    "LLM", "HIGH",
                    "需求与 AC 均未覆盖异常场景（超时/并发/重复提交/失败补偿）",
                    "为主流程补充至少一条异常路径 AC，例如「取消请求超时后重试不产生重复回补」"));
        }
        if (lower.contains("金额") || lower.contains("库存") || lower.contains("支付")) {
            byDimension.get(CheckDimension.RISK).add(new CheckItem(
                    "LLM", "MEDIUM",
                    "涉及资金/库存类资源变更，存在一致性风险",
                    "明确并发下的一致性策略（锁/幂等键/对账），并写进 AC"));
        }
        if (knowledgeBlock != null && !knowledgeBlock.isBlank() && !lower.contains("规范")) {
            byDimension.get(CheckDimension.RULE_CONFLICT).add(new CheckItem(
                    "LLM", "LOW",
                    "未显式对照项目知识库中的团队规范/业务规则",
                    "核对知识库相关条目，确认需求与既有规则无冲突后在描述中注明"));
        }
        List<DimensionReport> reports = new ArrayList<>();
        for (CheckDimension dimension : CheckDimension.values()) {
            reports.add(new DimensionReport(dimension.name(), List.copyOf(byDimension.get(dimension))));
        }
        return new LlmCheckResult(List.copyOf(reports), "mock-requirement-check", 0);
    }

    private boolean containsAny(String text, String[] hints) {
        for (String hint : hints) {
            if (text.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}
