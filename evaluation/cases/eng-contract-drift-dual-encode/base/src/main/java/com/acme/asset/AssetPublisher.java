package com.acme.asset;

import org.springframework.stereotype.Service;

@Service
public class AssetPublisher {

    private final AssetStore store;

    public AssetPublisher(AssetStore store) {
        this.store = store;
    }

    public String publish(String bucket, String objectKey, byte[] content) {
        store.put(bucket, objectKey, content);
        return AssetReferenceCodec.encode(bucket, objectKey);
    }
}
