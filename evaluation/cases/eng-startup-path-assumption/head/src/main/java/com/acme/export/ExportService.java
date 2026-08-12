package com.acme.export;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class ExportService {

    private final Clock clock;
    private final ArchiveStorageService archiveStorage;

    public ExportService(Clock clock, ArchiveStorageService archiveStorage) {
        this.clock = clock;
        this.archiveStorage = archiveStorage;
    }

    public ExportResult export(String projectCode, List<String> rows) {
        StringBuilder csv = new StringBuilder("code,value\n");
        for (String row : rows) {
            csv.append(projectCode).append(',').append(row).append('\n');
        }
        Instant generatedAt = Instant.now(clock);
        byte[] payload = csv.toString().getBytes(StandardCharsets.UTF_8);
        ExportResult result = new ExportResult(projectCode, generatedAt, payload);
        archiveStorage.store(result);
        return result;
    }
}
