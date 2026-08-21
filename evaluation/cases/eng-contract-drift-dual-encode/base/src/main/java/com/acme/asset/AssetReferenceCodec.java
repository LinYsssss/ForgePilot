package com.acme.asset;

public final class AssetReferenceCodec {

    private AssetReferenceCodec() {
    }

    public static String encode(String bucket, String objectKey) {
        return bucket + ":" + objectKey;
    }

    public static AssetReference decode(String reference) {
        int separator = reference.indexOf(':');
        if (separator < 0) {
            throw new IllegalArgumentException("malformed asset reference: " + reference);
        }
        return new AssetReference(reference.substring(0, separator), reference.substring(separator + 1));
    }
}
