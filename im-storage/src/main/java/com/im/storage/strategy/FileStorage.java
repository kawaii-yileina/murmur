package com.im.storage.strategy;

public interface FileStorage {
    String upload(byte[] data, String fileName, String contentType);
    byte[] download(String fileKey);
    String getPresignedUrl(String fileKey, int expirySeconds);
    void delete(String fileKey);
}
