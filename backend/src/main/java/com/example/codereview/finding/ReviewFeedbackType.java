package com.example.codereview.finding;

/**
 * 人工反馈的三种裁定形态。
 *
 * <p>枚举常量名即 REST 请求里的 {@code type} 字符串与导出行里的 {@code type} 字段,
 * r8 回灌流程按它分拣样本——改名等于破坏契约,只许新增。
 */
public enum ReviewFeedbackType {

    /** 误报:Agent 报了,人裁定不是问题。必须挂靠 findingId。 */
    FINDING_FALSE_POSITIVE,

    /** 确认:Agent 报了,人裁定确属问题。必须挂靠 findingId。 */
    FINDING_CONFIRMED,

    /** 漏报补录:Agent 没报,人补记。没有 finding 可挂,用 path+line+category+note 描述位置。 */
    MISS_REPORT;

    /** 该类型是否必须挂靠既有 finding;MISS_REPORT 与挂靠互斥(它记录的正是"不存在的 finding")。 */
    public boolean requiresFinding() {
        return this != MISS_REPORT;
    }
}
