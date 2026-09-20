package com.im.storage.strategy;

import java.io.OutputStream;

public interface FileStorage {
    String upload(byte[] data, String fileName, String contentType);
    void download(String fileKey, OutputStream out);
    String getPresignedUrl(String fileKey, int expirySeconds);
    void delete(String fileKey);
}
