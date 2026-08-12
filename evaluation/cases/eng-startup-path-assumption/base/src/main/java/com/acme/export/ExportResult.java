package com.acme.export;

import java.time.Instant;

public record ExportResult(String projectCode, Instant generatedAt, byte[] payload) {
}
