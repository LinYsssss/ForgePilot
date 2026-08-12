package com.acme.asset;

import org.springframework.stereotype.Service;

@Service
public class AssetPublisher {

    private final AssetStore store;

    public AssetPublisher(AssetStore store) {
        this.store = store;
    }

    public String publish(String region, String bucket, String objectKey, byte[] content) {
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException("region is required for v2 asset references");
        }
        store.put(bucket, objectKey, content);
        return AssetReferenceCodec.encode(region, bucket, objectKey);
    }
}
