package com.forgepilot.knowledge;

/** 文档的入库状态。FAILED 的文档必须带上失败原因（ARCHITECTURE.md 6）。 */
public enum KnowledgeStatus {
    PENDING,
    READY,
    FAILED
}
