package com.forgepilot.requirement;

/**
 * 针对某条需求某一次修订的一次性实现建议。
 *
 * <p>回答中点名了具体修订，因为建议描述的正是那次修订：文本是不可变的，
 * 所以为修订 2 生成的建议在修订 3 发布之后依然是关于修订 2 的真话；
 * 而看不到用的是哪次修订的读者，就无法判断这条建议是否仍然适用。
 */
public record ImplementationGuidance(long requirementId, long revisionId, int revisionSeq,
        String guidance) {
}
