package com.example.codereview.agent.orchestration;

import com.example.codereview.git.GitCliService;
import com.example.codereview.patch.PatchCandidate;
import com.example.codereview.patch.PatchCandidateRepository;
import com.example.codereview.repo.CodeRepositoryEntity;
import com.example.codereview.repo.CodeRepositoryJpaRepository;
import com.example.codereview.sandbox.WorkspaceArchiveReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 工作区归档的唯一生产者与生命周期管理。
 *
 * <p>此前整条链路没有任何写入方:后端只凭空编出引用字符串,共享卷里根本没有文件,
 * Runner 即使接受了引用也解析不到归档。现在由本服务在派发沙箱作业前落盘:
 *
 * <ul>
 *   <li>Agent 取证归档:head 树 + 预置 base→head diff(Runner 的 git.diff 只读预置文件);</li>
 *   <li>补丁验证归档:head 树 + {@code .reposage/candidate.patch};</li>
 *   <li>清理:正常完成的 Run 在发布步骤就地清理;FAILED/TIMED_OUT 保留归档以便运维
 *       reopenForRetry 续跑,连同崩溃残留一起交给 TTL 清扫兜底,防共享卷膨胀。</li>
 * </ul>
 *
 * <p>归档根默认 /app/archives(部署时挂 sandbox_archives 卷,backend 读写、Runner 只读)。
 * 刻意不在构造期创建目录:CI runner 非 root,/app 不可写,构造期 IO 会让所有
 * Spring 上下文测试拉不起来(F-02 教训)。
 */
@Service
public class WorkspaceArchiveService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceArchiveService.class);

    private final GitCliService gitCli;
    private final CodeRepositoryJpaRepository repositories;
    private final PatchCandidateRepository patches;
    private final Path archiveRoot;
    private final Duration archiveTtl;

    public WorkspaceArchiveService(
            GitCliService gitCli,
            CodeRepositoryJpaRepository repositories,
            PatchCandidateRepository patches,
            @Value("${app.sandbox.archive-root:/app/archives}") String archiveRoot,
            @Value("${app.sandbox.archive-ttl:PT6H}") Duration archiveTtl) {
        this.gitCli = gitCli;
        this.repositories = repositories;
        this.patches = patches;
        this.archiveRoot = Path.of(archiveRoot).toAbsolutePath().normalize();
        this.archiveTtl = archiveTtl;
    }

    /** 产出 Agent 取证归档并返回其契约引用(编码即校验,命名含 runId+sha 天然幂等可重试)。 */
    public String produceForAgentRun(Long repositoryId, Long agentRunId, String baseSha, String headSha) {
        String reference = WorkspaceArchiveReference.forAgentRun(agentRunId, headSha);
        CodeRepositoryEntity repository = requireRepository(repositoryId);
        gitCli.archiveForReview(repository, baseSha, headSha, archiveRoot.resolve(reference));
        return reference;
    }

    /** 产出补丁验证归档(head 树 + 候选补丁)并返回其契约引用。 */
    public String produceForPatch(Long repositoryId, PatchCandidate patch) {
        String reference = WorkspaceArchiveReference.forPatch(patch.getId(), patch.getPatchHash());
        CodeRepositoryEntity repository = requireRepository(repositoryId);
        gitCli.archiveWithFiles(repository, patch.getHeadSha(),
                Map.of("candidate.patch", patch.getPatchContent()), archiveRoot.resolve(reference));
        return reference;
    }

    /**
     * 尽力清理一次 Run 产出的全部归档(取证归档 + 该 Run 的补丁归档)。
     * 契约上永不抛出:清理失败只记日志,由 TTL 清扫兜底,不能反过来影响调用方的步骤结果。
     */
    public void cleanupForRun(Long agentRunId, String headSha) {
        try {
            deleteIfExists(WorkspaceArchiveReference.forAgentRun(agentRunId, headSha));
            for (PatchCandidate patch : patches.findByAgentRunIdOrderByIdAsc(agentRunId)) {
                deleteIfExists(WorkspaceArchiveReference.forPatch(patch.getId(), patch.getPatchHash()));
            }
        } catch (RuntimeException ex) {
            log.warn("Agent run {} 的工作区归档清理失败,交由 TTL 清扫兜底", agentRunId, ex);
        }
    }

    /**
     * TTL 兜底清扫。只在 app.agent.scheduling.enabled 打开时随调度基础设施生效
     * (与 Outbox 排空、恢复 watchdog 同一开关),测试上下文默认没有后台线程。
     */
    @Scheduled(fixedDelay = 900_000, initialDelay = 900_000)
    public void sweepExpiredArchives() {
        if (archiveTtl == null || archiveTtl.isZero() || archiveTtl.isNegative()
                || Files.notExists(archiveRoot)) {
            return;
        }
        Instant cutoff = Instant.now().minus(archiveTtl);
        try (Stream<Path> files = Files.list(archiveRoot)) {
            files.filter(Files::isRegularFile).forEach(file -> sweepOne(file, cutoff));
        } catch (IOException ex) {
            log.warn("工作区归档清扫失败,将在下一轮重试", ex);
        }
    }

    private void sweepOne(Path file, Instant cutoff) {
        try {
            if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ignored) {
            // 单个文件失败(并发删除、权限抖动)不应中断整轮清扫。
        }
    }

    private void deleteIfExists(String reference) {
        try {
            Files.deleteIfExists(archiveRoot.resolve(reference));
        } catch (IOException ex) {
            log.warn("工作区归档 {} 删除失败,交由 TTL 清扫兜底", reference, ex);
        }
    }

    private CodeRepositoryEntity requireRepository(Long repositoryId) {
        return repositories.findById(repositoryId)
                .orElseThrow(() -> new IllegalStateException("code repository is missing: " + repositoryId));
    }
}
