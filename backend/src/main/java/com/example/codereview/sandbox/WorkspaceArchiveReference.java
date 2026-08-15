package com.example.codereview.sandbox;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@link SandboxJob#workspaceArchiveRef()} 里那个工作区归档引用的唯一真源。
 *
 * <p>backend 与 runner 各留一份同构镜像(与 {@link SandboxJob}/{@link SandboxJobSigner} 同套路):
 * backend 负责编码,runner 负责解析,两边各有一个 golden 线格式测试钉住同一批字面量,
 * 谁都无法单方面漂移。之所以要这样,是因为两侧真的漂过——backend 发出的是
 * {@code workspace://...},而 runner 把一切含冒号的引用判为非法,于是生产上归档交接无条件失败。
 *
 * <p>引用刻意设计成一个**裸文件名**:没有 scheme,没有目录分隔符。所有语法层安全规则
 * (scheme、路径穿越、字符白名单、长度)都收在 {@link #parse} 里;把名字落到归档根目录之下、
 * realpath 围栏、以及「必须是普通文件」的检查,仍然是 runner 的职责。
 */
public final class WorkspaceArchiveReference {

    /** 长度上限,沿用 backend 历史上的请求校验口径。 */
    public static final int MAX_LENGTH = 512;

    /** 裸文件名:首字符必须是字母数字(挡掉开头的 {@code -} 与 {@code .}),不含分隔符。 */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern HEX_ID = Pattern.compile("[0-9a-fA-F]{7,80}");

    private WorkspaceArchiveReference() {
    }

    /** 编码 agent-run 工作区归档的引用(仓库树 + 已备好的 diff)。 */
    public static String forAgentRun(Long agentRunId, String headSha) {
        if (agentRunId == null || agentRunId <= 0) {
            throw new IllegalArgumentException("agentRunId is required");
        }
        if (headSha == null || !HEX_ID.matcher(headSha).matches()) {
            throw new IllegalArgumentException("head SHA is invalid for workspace archive");
        }
        return parse("agent-run-" + agentRunId + "-" + headSha.toLowerCase(Locale.ROOT) + ".tar");
    }

    /** 编码补丁校验工作区归档的引用(仓库树 + 候选补丁)。 */
    public static String forPatch(Long patchId, String patchHash) {
        if (patchId == null || patchId <= 0) {
            throw new IllegalArgumentException("persisted patch candidate is required");
        }
        if (patchHash == null || !HEX_ID.matcher(patchHash).matches()) {
            throw new IllegalArgumentException("patch hash is invalid for workspace archive");
        }
        return parse("patch-" + patchId + "-" + patchHash.toLowerCase(Locale.ROOT) + ".tar");
    }

    /**
     * 校验引用,通过则原样返回这个安全的裸文件名。拒绝 null、空白、超长、路径穿越
     * ({@code ..}),以及一切落在字符白名单之外的输入——白名单本身就顺带挡掉了任何 scheme
     * ({@code :})、任何路径分隔符({@code /}、{@code \}),以及开头的 {@code -} 与 {@code .}。
     */
    public static String parse(String reference) {
        if (reference == null || reference.isBlank() || reference.length() > MAX_LENGTH
                || reference.contains("..") || !SAFE_NAME.matcher(reference).matches()) {
            throw new IllegalArgumentException("workspace archive reference is invalid");
        }
        return reference;
    }
}
