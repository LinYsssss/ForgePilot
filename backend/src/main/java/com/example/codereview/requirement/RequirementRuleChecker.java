package com.example.codereview.requirement;

import com.example.codereview.requirement.RequirementCheckDtos.CheckDimension;
import com.example.codereview.requirement.RequirementCheckDtos.CheckItem;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 体检流水线的确定性规则层(P2,R3):纯代码、零 token,先于 LLM 运行。
 * 规则只覆盖"机器能可靠判定"的形态问题(缺段落、AC 数量、模糊词、无法验证的表述);
 * 语义类问题(规则冲突、异常场景遗漏)留给 LLM 层。
 */
@Component
public class RequirementRuleChecker {

    /** AC 中出现即判"表述模糊"的词——验收时无法二值判定。 */
    private static final Pattern VAGUE_WORDS =
            Pattern.compile("尽量|适当|大概|基本上|等等|相关(?:的)?(?:内容|功能|逻辑)|友好|良好|较?快|尽快|合理");

    /** 可测性启发:含数字/时限/枚举断言之一,或含明确的动作+结果连接词。 */
    private static final Pattern TESTABLE_HINT =
            Pattern.compile("[0-9０-９]|不得|必须|应当|应该|禁止|唯一|之内|以内|小于|大于|等于|返回|提示|拒绝|失败|成功");

    public record RuleFinding(CheckDimension dimension, CheckItem item) {
    }

    public List<RuleFinding> check(RequirementEntity requirement, List<AcceptanceCriterionEntity> criteria) {
        List<RuleFinding> findings = new ArrayList<>();
        if (requirement.getTitle() == null || requirement.getTitle().strip().length() < 4) {
            findings.add(rule(CheckDimension.CLARITY, "MEDIUM",
                    "标题过短，难以概括需求意图", "用「动作 + 对象 + 结果」的形式重写标题"));
        }
        if (isBlank(requirement.getBackground())) {
            findings.add(rule(CheckDimension.COMPLETENESS, "MEDIUM",
                    "缺少背景说明", "补充为什么要做这件事：业务动机、现状痛点"));
        }
        if (isBlank(requirement.getDescription())) {
            findings.add(rule(CheckDimension.COMPLETENESS, "HIGH",
                    "缺少需求描述", "补充要做成什么样子：功能行为、边界与约束"));
        }
        if (criteria.isEmpty()) {
            findings.add(rule(CheckDimension.TESTABILITY, "HIGH",
                    "没有任何验收标准（AC）", "至少补充 2 条可二值判定的 AC，后续一致性审查以 AC 为锚点"));
        } else {
            if (criteria.size() < 2) {
                findings.add(rule(CheckDimension.COMPLETENESS, "LOW",
                        "验收标准只有 1 条，通常覆盖不了主流程 + 异常路径", "按正常路径与异常路径分别补充 AC"));
            }
            for (AcceptanceCriterionEntity ac : criteria) {
                String text = ac.getText() == null ? "" : ac.getText();
                if (VAGUE_WORDS.matcher(text).find()) {
                    findings.add(rule(CheckDimension.CLARITY, "MEDIUM",
                            "AC" + ac.getSeq() + " 含模糊表述，验收时无法二值判定：「" + abbreviate(text) + "」",
                            "改写为可观察、可量化的断言（明确数值、时限或确定行为）"));
                }
                if (!TESTABLE_HINT.matcher(text).find()) {
                    findings.add(rule(CheckDimension.TESTABILITY, "LOW",
                            "AC" + ac.getSeq() + " 缺少可验证的断言特征：「" + abbreviate(text) + "」",
                            "补充明确的预期结果（返回什么、状态变成什么、多长时间内完成）"));
                }
            }
        }
        return findings;
    }

    private RuleFinding rule(CheckDimension dimension, String severity, String message, String suggestion) {
        return new RuleFinding(dimension, new CheckItem("RULE", severity, message, suggestion));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String abbreviate(String value) {
        String normalized = value.strip();
        return normalized.length() <= 40 ? normalized : normalized.substring(0, 40) + "…";
    }
}
