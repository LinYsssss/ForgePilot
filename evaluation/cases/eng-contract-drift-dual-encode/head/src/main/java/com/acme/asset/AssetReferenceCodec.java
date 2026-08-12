package com.acme.asset;

/**
 * Canonical encoder/decoder for asset references.
 * v2 wire format: v2|region|bucket|objectKey; legacy bucket:objectKey still decodes.
 * Every component that renders or parses a reference must go through this codec.
 */
public final class AssetReferenceCodec {

    static final String V2_PREFIX = "v2";
    static final String V2_SEPARATOR = "|";

    private AssetReferenceCodec() {
    }

    public static String encode(String region, String bucket, String objectKey) {
        return String.join(V2_SEPARATOR, V2_PREFIX, region, bucket, objectKey);
    }

    public static AssetReference decode(String reference) {
        String[] parts = reference.split("\\|");
        if (parts.length == 4 && V2_PREFIX.equals(parts[0])) {
            return new AssetReference(parts[1], parts[2], parts[3]);
        }
        int separator = reference.indexOf(':');
        if (separator < 0) {
            throw new IllegalArgumentException("malformed asset reference: " + reference);
        }
        return new AssetReference("default", reference.substring(0, separator), reference.substring(separator + 1));
    }
}
