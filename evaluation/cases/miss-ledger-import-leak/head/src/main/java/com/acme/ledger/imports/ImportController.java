package com.acme.ledger.imports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 台账导入入口。
 *
 * <p>上传文件由网关落到临时目录后传入路径；入口层做大小与扩展名门槛，
 * 处理完由网关负责清理临时文件。
 */
public class ImportController {

    /** 上传文件大小上限：20MB。 */
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private final ImportJobService importJobService;

    public ImportController(ImportJobService importJobService) {
        this.importJobService = importJobService;
    }

    /** POST /ledger/import */
    public ImportJobService.ImportSummary importCsv(Long tenantId, Path uploadedFile)
            throws IOException {
        String name = String.valueOf(uploadedFile.getFileName());
        if (!name.endsWith(".csv")) {
            throw new IllegalArgumentException("仅支持 .csv 文件");
        }
        long size = Files.size(uploadedFile);
        if (size == 0) {
            throw new IllegalArgumentException("文件为空");
        }
        if (size > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件超过 20MB 上限");
        }
        return importJobService.runImport(tenantId, uploadedFile);
    }
}
