package com.forgepilot.knowledge;

/** 一份文档究竟属于某一条需求，还是属于整个项目（D005）。 */
public enum KnowledgeSourceType {
    /** 作用域恰好是一条需求；只对该需求的 AI 场景可见。 */
    REQUIREMENT_ATTACHMENT,
    /** 公共项目知识；不携带任何需求作用域。 */
    PROJECT_KNOWLEDGE
}
