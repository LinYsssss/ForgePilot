package com.forgepilot.review;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 一条 Finding 跨轮次携带的三个确定性 key（D009、ARCHITECTURE.md 3.6.1 与 3.6.2），
 * 计算方式与 {@code ReviewInputFingerprint} 生成摘要的方式一致：
 * 每个字段都用 NUL 字节框起来，而 NUL 不可能出现在路径、证据片段或已存 patch 中，
 * 因此编码是单射的，两份不同的输入不可能碰撞成同一个摘要。
 *
 * <p><strong>两个哈希都不覆盖模型写出的任何一个散文字符。</strong>
 * 这正是整套机制的要点。只有当两个哈希都未变化时驳回才被继承；
 * 因此若某个哈希覆盖了模型的描述文字，下一轮的换个说法要么会丢掉一个
 * 本应有效的抑制项，要么——更糟——会去抑制一条其代码或需求其实已经
 * 在它脚下变动过的 finding。
 *
 * <p>这三条都是冻结规则。{@link #RULE_VERSION} 的存在，
 * 使得改动它们成为一个有可见后果的、刻意的动作，而不是一次静默的变更。
 */
final class FindingKeys {

    /**
     * 它是每一个 {@code basis_hash} 的组成部分。递增它会改变所有 basis hash，
     * 从而丢弃所有被继承的抑制项——这正是预期效果：
     * 如果确定性规则变了，那么人此前作出的驳回是针对另一套依据作出的，
     * 不得未经复核就继续沿用。
     */
    static final String RULE_VERSION = "1";

    /**
     * 本流水线实际会遇到的**唯一**一种易变行号形态。patch 从 provider 那里
     * 以 unified diff 形式到达，而某个 hunk 上方一处无关的编辑，
     * 会在证据一个字节都没变的情况下改动这些数字。
     *
     * <p>收尾 {@code @@} 之后的上下文是**刻意保留**的——那是源码。
     * 此外不剥离任何其他形态的“行号”：一条会去掉开头 {@code 42: } 装订线的规则，
     * 同样会毁掉任何真正以数字开头的源码行，
     * 而本代码库根本不会产生那种形态。
     */
    private static final Pattern HUNK_HEADER =
            Pattern.compile("(?m)^@@ -\\d+(?:,\\d+)? \\+\\d+(?:,\\d+)? @@");
    private static final String HUNK_HEADER_PLACEHOLDER = "@@ @@";

    private static final byte SEPARATOR = 0x00;
    private static final byte PRESENT = '1';
    private static final byte ABSENT = '0';

    private FindingKeys() {
    }

    /**
     * 它一次干两件事（3.6.1）：单个 Review 内部的去重，
     * 以及同一个 PR 各次 Review 之间的匹配。
     *
     * <p>{@code path} 大小写敏感且绝不转小写——在大小写敏感的检出中，
     * {@code Api.java} 与 {@code api.java} 是两个文件，
     * 把它们折叠会让对其中一个的驳回抑制掉另一个里的 finding。
     * {@code REQUIREMENT} 类 finding 还额外携带 {@code requirementId} 与
     * {@code acKey}；用 {@code acKey} 而不是验收条件的行 id，
     * 是因为行 id 会随每次修订发布而变，而它所指代的业务身份不会（D011）。
     *
     * <p>结果是一个摘要而不是可读的拼接串，因为那个列是 {@code VARCHAR(255)}：
     * 一个合法的深层路径加上类别就会超长，PostgreSQL 会以 22001 拒绝插入——
     * 而为了塞下去做截断，则会把两条不同的 finding 静默合并成一个 key。
     */
    static String findingKey(FindingType findingType, String path, Integer line, String category,
            Long requirementId, String acKey) {
        MessageDigest digest = sha256();
        field(digest, findingType.name());
        field(digest, path);
        // 归一化后的位置：如果 patch 能核实出行号就用它，否则留空
        // （3.5 禁止输出一个无法核实的行号）。
        field(digest, line == null ? null : line.toString());
        field(digest, normalizeCategory(category));
        field(digest, requirementId == null ? null : requirementId.toString());
        field(digest, acKey);
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 只覆盖确定性的源码证据：统一行尾、替换掉 diff hunk 头部的易变数字，
     * 此外一律不动。
     *
     * <p>空白<strong>不做</strong>折叠。Python 与 YAML 在不同缩进下含义不同，
     * 因此通用的折叠会让两段确实不同的源码哈希成同一个值——
     * 而一个无视缩进的抑制项，就是一个能在真实变更之后继续存活的抑制项。
     */
    static String evidenceHash(String excerpt) {
        MessageDigest digest = sha256();
        field(digest, normalizeEvidence(excerpt));
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 覆盖这条 finding 是<em>对照什么</em>作出判断的：被引用的需求与验收条件
     * 在本次 Review 所用修订中的原文、被召回的知识片段的哈希，以及规则版本。
     *
     * <p>片段哈希会排序并去重，使 provider 的召回顺序无法改变结果——
     * 「被引用的来源集合」才是事实，它们的顺序不是。片段以自身的哈希而非文本
     * 进入计算：ARCHITECTURE.md 3.5 之所以存储不可变片段加哈希，
     * 正是为了让日后对知识文档的编辑无法改写一次过往 Review 当初的含义。
     */
    static String basisHash(String requirementText, String acKey, String acText,
            Collection<String> knowledgeExcerptHashes) {
        MessageDigest digest = sha256();
        field(digest, RULE_VERSION);
        field(digest, requirementText);
        field(digest, acKey);
        field(digest, acText);
        knowledgeExcerptHashes.stream().distinct().sorted().forEach(hash -> field(digest, hash));
        return HexFormat.of().formatHex(digest.digest());
    }

    static String normalizeEvidence(String excerpt) {
        String unifiedNewlines = excerpt.replace("\r\n", "\n").replace('\r', '\n');
        return HUNK_HEADER.matcher(unifiedNewlines).replaceAll(HUNK_HEADER_PLACEHOLDER);
    }

    /**
     * 类别是回答 schema 里的一个标签，不是散文，而且它参与的是 key 而非哈希。
     * 大小写与首尾空白在这里——也只在这里——被折叠，
     * 因为 {@code Null deref} 与 {@code null-deref} 指的是同一个类别，
     * 而仅在大小写上不同的两个路径指的是两个文件。
     */
    static String normalizeCategory(String category) {
        return category == null ? "" : category.strip().toLowerCase(Locale.ROOT);
    }

    /** 「缺席」与「空」绝不能哈希成同一个值，因此「是否存在」自占一个字节。 */
    private static void field(MessageDigest digest, String value) {
        digest.update(value == null ? ABSENT : PRESENT);
        if (value != null) {
            digest.update(value.getBytes(UTF_8));
        }
        digest.update(SEPARATOR);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
