package com.example.reposage.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 只解析「已为可信 Runner 预置好的本地临时归档」引用,不接受任何其它来源。 */
public final class WorkspaceArchiveResolver {

    private final Path archiveRoot;

    public WorkspaceArchiveResolver(Path archiveRoot) throws IOException {
        Files.createDirectories(archiveRoot);
        this.archiveRoot = archiveRoot.toRealPath();
    }

    public Path resolve(String reference) throws IOException {
        String fileName;
        try {
            // 全部语法规则(scheme、路径穿越、白名单、长度)都收在与 backend 编码器互为镜像的
            // 编解码器里,生产方与校验方因此再也无法各自漂移。
            // 这里原先那套手写校验会无条件拒收 backend 的真实输出——就是它把交接打断的。
            fileName = WorkspaceArchiveReference.parse(reference);
        } catch (IllegalArgumentException ex) {
            throw new SecurityException(ex.getMessage());
        }
        Path normalized = archiveRoot.resolve(fileName).toAbsolutePath().normalize();
        if (!normalized.startsWith(archiveRoot)) {
            throw new SecurityException("workspace archive reference escapes archive root");
        }
        Path real = normalized.toRealPath();
        if (!real.startsWith(archiveRoot) || !Files.isRegularFile(real)) {
            throw new SecurityException("workspace archive reference escapes archive root");
        }
        return real;
    }
}
