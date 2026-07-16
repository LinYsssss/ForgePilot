package com.example.reposage.sandbox;

public record RepositoryArchiveLimits(long maxFiles, long maxFileBytes, long maxTotalBytes) {

    public RepositoryArchiveLimits {
        if (maxFiles <= 0 || maxFileBytes <= 0 || maxTotalBytes <= 0 || maxFileBytes > maxTotalBytes) {
            throw new IllegalArgumentException("invalid repository archive limits");
        }
    }
}
