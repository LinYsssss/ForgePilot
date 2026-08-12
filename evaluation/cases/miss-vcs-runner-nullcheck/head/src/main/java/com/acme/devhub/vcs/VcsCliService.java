package com.acme.devhub.vcs;

import com.acme.devhub.common.SecretBox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * git 命令行封装：镜像同步、变更清单、diff 导出。
 *
 * <p>子进程执行边界（命令组装、askpass、超时、抽干、脱敏）已下沉到
 * {@link VcsProcessRunner}；本类只保留 git 语义，公共签名零变更。
 */
public class VcsCliService {

    private final VcsProcessRunner runner;
    private final Path workRoot;

    public VcsCliService(SecretBox secretBox, Path workRoot) {
        this.runner = new VcsProcessRunner(secretBox, workRoot);
        this.workRoot = workRoot;
    }

    /** 同步镜像仓库（不存在则克隆，存在则 fetch）。 */
    public void syncMirror(RepoHandle repo) {
        Path mirror = mirrorPath(repo);
        if (Files.isDirectory(mirror.resolve("objects"))) {
            runner.run(mirror, repo, "fetch", "--prune", "origin");
        } else {
            runner.run(workRoot, repo, "clone", "--mirror", repo.getRemoteUrl(), mirror.toString());
        }
    }

    /** 两个提交之间的变更文件清单。 */
    public List<String> listChangedFiles(RepoHandle repo, String baseSha, String headSha) {
        String output = runner.run(mirrorPath(repo), repo,
                "diff", "--name-only", baseSha + ".." + headSha);
        List<String> files = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (!line.isBlank()) {
                files.add(line.trim());
            }
        }
        return files;
    }

    /** 导出两个提交之间的统一 diff 文本。 */
    public String exportDiff(RepoHandle repo, String baseSha, String headSha) {
        return runner.run(mirrorPath(repo), repo,
                "diff", "--unified=3", baseSha + ".." + headSha);
    }

    private Path mirrorPath(RepoHandle repo) {
        return workRoot.resolve(repo.getMirrorDirName());
    }
}
