package com.acme.ingest;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class ManifestImporter {

    public List<ManifestEntry> parse(String manifestContent) {
        List<ManifestEntry> entries = new ArrayList<>();
        for (String line : manifestContent.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] columns = trimmed.split("\\s+");
            if (columns.length < 2) {
                throw new IllegalArgumentException("malformed manifest line: " + trimmed);
            }
            entries.add(new ManifestEntry(columns[0], columns[1]));
        }
        return entries;
    }
}
