package com.im.storage.impl.minio;

import com.im.storage.strategy.FileStorage;
import io.minio.*;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.io.ByteArrayInputStream;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "storage.type", havingValue = "minio")
public class MinioStorage implements FileStorage {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    @Override
    public String upload(byte[] data, String fileName, String contentType) {
        try {
            String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf(".")) : "";
            String objectName = UUID.randomUUID() + ext;

            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .object(objectName)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(contentType)
                    .build());

            log.info("File uploaded: {}", objectName);
            return objectName;
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload file", e);
        }
    }

    @Override
    public byte[] download(String fileKey) {
        try {
            GetObjectResponse response = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .object(fileKey)
                    .build());
            return response.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to download file", e);
        }
    }

    @Override
    public String getPresignedUrl(String fileKey, int expirySeconds) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(minioProperties.getBucket())
                    .object(fileKey)
                    .expiry(expirySeconds, TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate presigned URL", e);
        }
    }

    @Override
    public void delete(String fileKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .object(fileKey)
                    .build());
            log.info("File deleted: {}", fileKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete file", e);
        }
    }
}
