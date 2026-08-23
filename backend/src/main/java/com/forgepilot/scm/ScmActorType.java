package com.forgepilot.scm;

/** 是谁改动了 PR 的需求关联。没有第三种情况，也不存在匿名的人工操作。 */
public enum ScmActorType {
    USER,
    SYSTEM
}
