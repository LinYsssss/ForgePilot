package com.forgepilot.scm;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 归一化后的审查输入的确定性哈希。
 *
 * <p>输入包括：仓库身份、base 与 head，以及带全部 patch 的变更文件清单。
 * 明确<em>不</em>包括 {@code source_revision} 与 {@code source_updated_at}——
 * 那两者只用于事件定序，绝不能凭空铸造出自己的身份——也不包括需求关联，
 * 后者是 Review 身份中另一个独立的组成部分。
 *
 * <p>归一化规则：文件按路径字节序排列、路径大小写敏感、patch 按 UTF-8 字节
 * 且不改动行尾（CRLF 与 LF 是**确实不同**的 diff，折叠它们会让两份不同的输入
 * 撞成同一个值），每个字段都用 NUL 分隔符框起来。NUL 是路径和已存 patch 中
 * 都不可能出现的唯一字节——PostgreSQL 会以 22021 拒绝文本中的 NUL——
 * 这使编码成为单射，因此两份不同的清单不可能产生同一个摘要。
 *
 * <p>这条规则已经冻结。改动它会让所有已存指纹失效，从而让每一个由它派生出的
 * Review 身份一并失效——这正是 {@code ReviewInputFingerprintTest} 把一个期望
 * 摘要写成字面量钉死的原因。
 */
final class ReviewInputFingerprint {

    private static final byte SEPARATOR = 0x00;
    private static final byte PATCH_PRESENT = '1';
    private static final byte PATCH_ABSENT = '0';

    private ReviewInputFingerprint() {
    }

    static String of(String provider, String instanceIdentity, String externalId,
            PullRequestSnapshot snapshot) {
        MessageDigest digest = sha256();
        field(digest, provider);
        field(digest, instanceIdentity);
        field(digest, externalId);
        field(digest, snapshot.baseSha());
        field(digest, snapshot.headSha());
        for (ChangedFile file : ChangedFile.canonicalOrder(snapshot.changedFiles())) {
            field(digest, file.path());
            field(digest, file.changeType());
            digest.update(file.patch() == null ? PATCH_ABSENT : PATCH_PRESENT);
            if (file.patch() != null) {
                digest.update(file.patch().getBytes(UTF_8));
            }
            digest.update(SEPARATOR);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void field(MessageDigest digest, String value) {
        digest.update(value.getBytes(UTF_8));
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
