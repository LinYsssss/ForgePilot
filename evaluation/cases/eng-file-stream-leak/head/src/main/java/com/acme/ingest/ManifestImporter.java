package com.acme.ingest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class ManifestImporter {

    private static final String END_MARKER = "# end-of-manifest";

    public List<ManifestEntry> parse(Path manifestFile) {
        List<ManifestEntry> entries = new ArrayList<>();
        try {
            BufferedReader reader = Files.newBufferedReader(manifestFile);
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (END_MARKER.equals(trimmed)) {
                    return entries;
                }
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
        } catch (IOException e) {
            throw new UncheckedIOException("unable to read manifest " + manifestFile, e);
        }
    }
}
