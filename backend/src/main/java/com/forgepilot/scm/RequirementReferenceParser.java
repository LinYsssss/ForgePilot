package com.forgepilot.scm;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.forgepilot.requirement.RequirementDirectory;
import org.springframework.stereotype.Component;

/**
 * 解析 PR 分支名或标题中出现的第一个 {@code REQ-<n>}，其中 {@code <n>} 是全局的
 * {@code requirement.id}。
 *
 * <p>解析结果会按该 PR 自己的项目过滤，因此属于其他项目的 id 会被解析成
 * 「没有关联需求」，与压根没写过这个 token 完全一样——既不是错误，
 * 也永远不会阻塞入库。复合外键做不到这种过滤：它只会让整条插入失败，
 * 而捕获它后继续执行是被禁止的。因此这个问题在写行之前就通过
 * {@code requirement} 的只读 facade 问清楚；本类绝不能看到任何仓库。
 */
@Component
class RequirementReferenceParser {

    /**
     * 大小写敏感，并锚定在非字母数字的边界上。所有文档都把这个 token 写作
     * {@code REQ-}，因此宽松匹配等于凭空发明；边界则防止 {@code PREQ-7}
     * 被读成一个引用。
     */
    private static final Pattern REFERENCE = Pattern.compile("(?<![A-Za-z0-9])REQ-(\\d{1,18})");

    private final RequirementDirectory requirements;

    RequirementReferenceParser(RequirementDirectory requirements) {
        this.requirements = requirements;
    }

    /** 分支名优先于标题：分支名是开发者必须打对才能 push 的那一个。 */
    Optional<RequirementReference> resolve(long projectId, String branch, String title) {
        return firstReference(branch, "branch")
                .or(() -> firstReference(title, "title"))
                .filter(reference -> requirements.existsInProject(projectId, reference.requirementId()));
    }

    static Optional<RequirementReference> firstReference(String text, String source) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = REFERENCE.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new RequirementReference(Long.parseLong(matcher.group(1)), source));
    }

    record RequirementReference(long requirementId, String source) {
    }
}
