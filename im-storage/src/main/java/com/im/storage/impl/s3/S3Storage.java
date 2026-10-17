package com.im.storage.impl.s3;

import com.im.storage.exception.FileMissingException;
import com.im.storage.strategy.FileStorage;
import com.im.storage.strategy.MultipartInit;
import io.minio.BucketExistsArgs;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S3 兼容对象存储（如 SeaweedFS）。文件在多个应用实例间共享。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "storage.type", havingValue = "s3")
public class S3Storage implements FileStorage {

    private final MinioClient s3Client;
    private final S3Properties properties;

    /** 桶就绪标记：首次使用时惰性创建，避免启动期强依赖对象存储可用性 */
    private volatile boolean bucketReady = false;

    @Override
    public String upload(byte[] data, String fileName, String contentType) {
        ensureBucket();
        try {
            String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf(".")) : "";
            String objectName = UUID.randomUUID() + ext;

            s3Client.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
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
    public void download(String fileKey, OutputStream out) {
        try (GetObjectResponse response = s3Client.getObject(GetObjectArgs.builder()
                .bucket(properties.getBucket())
                .object(fileKey)
                .build())) {
            response.transferTo(out);
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                throw new FileMissingException(fileKey, e);
            }
            throw new RuntimeException("Failed to download file", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to download file", e);
        }
    }

    @Override
    public void delete(String fileKey) {
        try {
            s3Client.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(fileKey)
                    .build());
            log.info("File deleted: {}", fileKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete file", e);
        }
    }

    @Override
    public MultipartInit initMultipart(String fileName, String contentType) {
        ensureBucket();
        String ext = fileName != null && fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf(".")) : "";
        String objectName = UUID.randomUUID() + ext;
        return new MultipartInit(UUID.randomUUID().toString(), objectName);
    }

    @Override
    public void uploadPart(String fileKey, String uploadId, int partNumber, byte[] data) {
        try {
            s3Client.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(partObjectName(fileKey, partNumber))
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload part", e);
        }
    }

    @Override
    public String completeMultipart(String fileKey, String uploadId) {
        try {
            List<Integer> parts = listParts(fileKey, uploadId);
            String bucket = properties.getBucket();
            if (parts.size() == 1) {
                // 单分片直接拷贝为最终对象
                s3Client.copyObject(CopyObjectArgs.builder()
                        .bucket(bucket)
                        .object(fileKey)
                        .source(CopySource.builder()
                                .bucket(bucket)
                                .object(partObjectName(fileKey, parts.get(0)))
                                .build())
                        .build());
            } else {
                List<ComposeSource> sources = new ArrayList<>();
                for (int partNumber : parts) {
                    sources.add(ComposeSource.builder()
                            .bucket(bucket)
                            .object(partObjectName(fileKey, partNumber))
                            .build());
                }
                s3Client.composeObject(ComposeObjectArgs.builder()
                        .bucket(bucket)
                        .object(fileKey)
                        .sources(sources)
                        .build());
            }
            removeParts(fileKey, parts);
            log.info("Multipart upload completed: {}", fileKey);
            return fileKey;
        } catch (Exception e) {
            throw new RuntimeException("Failed to complete multipart upload", e);
        }
    }

    @Override
    public void abortMultipart(String fileKey, String uploadId) {
        try {
            removeParts(fileKey, listParts(fileKey, uploadId));
        } catch (Exception e) {
            log.warn("Failed to abort multipart upload {}: {}", fileKey, e.getMessage());
        }
    }

    @Override
    public List<Integer> listParts(String fileKey, String uploadId) {
        try {
            String prefix = fileKey + ".part";
            List<Integer> numbers = new ArrayList<>();
            for (Result<Item> result : s3Client.listObjects(ListObjectsArgs.builder()
                    .bucket(properties.getBucket())
                    .prefix(prefix)
                    .recursive(true)
                    .build())) {
                String name = result.get().objectName();
                try {
                    numbers.add(Integer.parseInt(name.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // 忽略不符合命名规则的对象
                }
            }
            numbers.sort(Integer::compareTo);
            return numbers;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list parts", e);
        }
    }

    /** 分片对象名：最终对象名 + .part + 分片号 */
    private String partObjectName(String fileKey, int partNumber) {
        return fileKey + ".part" + partNumber;
    }

    private void removeParts(String fileKey, List<Integer> parts) {
        for (int partNumber : parts) {
            try {
                s3Client.removeObject(RemoveObjectArgs.builder()
                        .bucket(properties.getBucket())
                        .object(partObjectName(fileKey, partNumber))
                        .build());
            } catch (Exception e) {
                log.warn("Failed to remove part {} of {}: {}", partNumber, fileKey, e.getMessage());
            }
        }
    }

    /**
     * 惰性创建桶：首次上传时确保 bucket 存在。
     * 多实例并发创建时，另一实例已创建的 AlreadyExists/BucketAlreadyOwnedByYou 视为成功。
     */
    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            String bucket = properties.getBucket();
            try {
                if (!s3Client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    try {
                        s3Client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                        log.info("Created bucket: {}", bucket);
                    } catch (Exception createErr) {
                        // 并发创建：确认桶已存在则忽略
                        if (!s3Client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                            throw createErr;
                        }
                    }
                }
                bucketReady = true;
            } catch (Exception e) {
                throw new RuntimeException("Failed to ensure bucket: " + bucket, e);
            }
        }
    }
}
