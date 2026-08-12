package com.acme.export;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @PostMapping("/api/exports")
    public ResponseEntity<byte[]> export(@RequestParam String projectCode, @RequestBody List<String> rows) {
        ExportResult result = exportService.export(projectCode, rows);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + result.projectCode() + ".csv")
                .contentType(MediaType.TEXT_PLAIN)
                .body(result.payload());
    }
}
