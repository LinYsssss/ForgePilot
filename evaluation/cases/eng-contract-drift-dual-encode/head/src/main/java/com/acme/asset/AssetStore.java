package com.acme.asset;

public interface AssetStore {

    void put(String bucket, String objectKey, byte[] content);
}
