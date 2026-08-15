package com.example.reposage.sandbox;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

/** 解包不可信的仓库归档,同时不允许任何路径逃逸(zip slip)或资源逃逸(解压炸弹)。 */
public final class RepositoryArchiveExtractor {

    private static final Pattern SUBMODULE_URL = Pattern.compile("(?im)^\\s*url\\s*=\\s*(\\S+)\\s*$");
    private final RepositoryArchiveLimits limits;
    private final RepositoryUrlPolicy urlPolicy;

    public RepositoryArchiveExtractor(RepositoryArchiveLimits limits, RepositoryUrlPolicy urlPolicy) {
        this.limits = limits;
        this.urlPolicy = urlPolicy;
    }

    public RepositoryArchiveSummary extract(Path archive, Path destination) throws IOException {
        if (archive == null || !Files.isRegularFile(archive)) {
            throw new SecurityException("repository archive is missing");
        }
        Files.createDirectories(destination);
        Path root = destination.toRealPath();
        long[] counters = new long[2];
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive), 64 * 1024);
             ArchiveInputStream<?> input = archiveStream(raw)) {
            ArchiveEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String relative = safeEntryName(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(root.resolve(relative));
                    continue;
                }
                if (isLink(entry)) {
                    throw new SecurityException("archive symbolic or hard links are forbidden");
                }
                counters[0]++;
                if (counters[0] > limits.maxFiles()) {
                    throw new SecurityException("archive file count exceeds limit");
                }
                Path target = root.resolve(relative).normalize();
                if (!target.startsWith(root)) {
                    throw new SecurityException("archive path escapes destination");
                }
                Files.createDirectories(target.getParent());
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                copyBounded(input, bytes, counters);
                Files.write(target, bytes.toByteArray());
                if (relative.equalsIgnoreCase(".gitmodules")) {
                    validateSubmodules(bytes.toString(StandardCharsets.UTF_8));
                }
            }
        } catch (org.apache.commons.compress.archivers.ArchiveException
                 | org.apache.commons.compress.compressors.CompressorException ex) {
            throw new SecurityException("unsupported or malformed repository archive", ex);
        }
        return new RepositoryArchiveSummary(counters[0], counters[1]);
    }

    private void copyBounded(InputStream input, ByteArrayOutputStream output, long[] counters) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        long fileBytes = 0;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            fileBytes += read;
            counters[1] += read;
            if (fileBytes > limits.maxFileBytes()) {
                throw new SecurityException("archive file size exceeds limit");
            }
            if (counters[1] > limits.maxTotalBytes()) {
                throw new SecurityException("archive total size exceeds limit");
            }
            output.write(buffer, 0, read);
        }
    }

    private ArchiveInputStream<?> archiveStream(InputStream input)
            throws IOException, org.apache.commons.compress.archivers.ArchiveException,
            org.apache.commons.compress.compressors.CompressorException {
        BufferedInputStream buffered = input instanceof BufferedInputStream value
                ? value
                : new BufferedInputStream(input);
        buffered.mark(16);
        byte[] magic = buffered.readNBytes(4);
        buffered.reset();
        if (magic.length >= 2 && magic[0] == 'P' && magic[1] == 'K') {
            return new ZipArchiveInputStream(buffered);
        }
        try {
            CompressorInputStream compressed = new CompressorStreamFactory().createCompressorInputStream(buffered);
            return new ArchiveStreamFactory().createArchiveInputStream(compressed);
        } catch (org.apache.commons.compress.compressors.CompressorException ex) {
            buffered.reset();
            return new ArchiveStreamFactory().createArchiveInputStream(buffered);
        }
    }

    private static String safeEntryName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new SecurityException("archive entry path is empty");
        }
        String name = rawName.replace('\\', '/');
        if (name.startsWith("/") || name.matches("^[A-Za-z]:/.*")) {
            throw new SecurityException("archive entry path is absolute");
        }
        Path normalized = Path.of(name).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..") || normalized.toString().equals(".")) {
            throw new SecurityException("archive entry path contains traversal");
        }
        return normalized.toString().replace('\\', '/');
    }

    private static boolean isLink(ArchiveEntry entry) {
        if (entry instanceof TarArchiveEntry tar) {
            return tar.isSymbolicLink() || tar.isLink();
        }
        return entry instanceof org.apache.commons.compress.archivers.zip.ZipArchiveEntry zip && zip.isUnixSymlink();
    }

    private void validateSubmodules(String content) {
        Matcher matcher = SUBMODULE_URL.matcher(content);
        while (matcher.find()) {
            urlPolicy.validateSubmoduleUrl(matcher.group(1));
        }
    }
}
