package com.example.codereview.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 五臂实验的 feature flags(P4a,父 design §7)。生产默认全开;实验按组合下发并随任务持久化
 * (flags_json),异步消费与复跑都按下单时的组合执行——生产链路与实验共用同一代码路径,
 * 这是论文可信度的关键约束。
 *
 * <p>五臂映射:Baseline=全关(纯 diff)/ A=+knowledge / B=+requirementContext /
 * C=A+B / D=C+evidenceVerification。
 */
public record ReviewFeatureFlags(boolean knowledge, boolean requirementContext, boolean evidenceVerification) {

    public static ReviewFeatureFlags productionDefaults() {
        return new ReviewFeatureFlags(true, true, true);
    }

    /** null/解析失败 → 生产默认(缺省路径必须与引入 flags 之前的行为一致)。 */
    public static ReviewFeatureFlags parse(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return productionDefaults();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return new ReviewFeatureFlags(
                    node.path("knowledge").asBoolean(true),
                    node.path("requirementContext").asBoolean(true),
                    node.path("evidenceVerification").asBoolean(true));
        } catch (Exception ex) {
            return productionDefaults();
        }
    }

    public String toJson() {
        return "{\"knowledge\":" + knowledge
                + ",\"requirementContext\":" + requirementContext
                + ",\"evidenceVerification\":" + evidenceVerification + "}";
    }
}
