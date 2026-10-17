package com.im.core.file.service;

import com.im.common.exception.BizException;
import com.im.storage.strategy.FileStorage;
import com.im.storage.strategy.MultipartInit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 分片上传会话管理：会话与已传分片存 Redis（多实例共享，TTL 自动过期），
 * 分片数据由 {@link FileStorage} 落到具体存储（S3 multipart / 本地临时目录合并）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultipartUploadService {

    /** 单文件上限（与 nginx / multipart 一致） */
    public static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
    /** 分片大小（S3 分片除最后一片外需 >= 5MB） */
    public static final int PART_SIZE = 5 * 1024 * 1024;
    /** 会话有效期（秒） */
    public static final long SESSION_TTL_SECONDS = 24 * 3600;

    private static final String SESSION_KEY = "im:upload:";
    private static final String PARTS_KEY = "im:upload:parts:";
    private static final String CLEANUP_KEY = "im:upload:cleanup:";
    private static final String ACTIVE_ZSET = "im:upload:active";

    private final StringRedisTemplate redis;
    private final FileStorage fileStorage;

    public Map<String, Object> init(Long ownerId, String fileName, String contentType, long size, int totalParts) {
        if (size <= 0 || size > MAX_FILE_BYTES) {
            throw new BizException(400, "文件大小不能超过 100MB");
        }
        int expectedParts = (int) ((size + PART_SIZE - 1) / PART_SIZE);
        if (totalParts != expectedParts) {
            throw new BizException(400, "分片数与文件大小不匹配");
        }
        MultipartInit init = fileStorage.initMultipart(fileName, contentType);
        String uploadId = UUID.randomUUID().toString();
        redis.opsForHash().putAll(SESSION_KEY + uploadId, Map.of(
                "ownerId", String.valueOf(ownerId),
                "fileKey", init.fileKey(),
                "storageUploadId", init.uploadId(),
                "size", String.valueOf(size),
                "totalParts", String.valueOf(totalParts)));
        redis.expire(SESSION_KEY + uploadId, Duration.ofSeconds(SESSION_TTL_SECONDS));
        redis.opsForHash().putAll(CLEANUP_KEY + uploadId, Map.of(
                "fileKey", init.fileKey(),
                "storageUploadId", init.uploadId()));
        redis.expire(CLEANUP_KEY + uploadId, Duration.ofSeconds(SESSION_TTL_SECONDS + 3600));
        redis.opsForZSet().add(ACTIVE_ZSET, uploadId, System.currentTimeMillis() / 1000.0 + SESSION_TTL_SECONDS);
        return Map.of("uploadId", uploadId, "partSize", PART_SIZE);
    }

    public void savePart(Long ownerId, String uploadId, int partNumber, byte[] data) {
        Map<Object, Object> session = requireSession(ownerId, uploadId);
        if (partNumber < 1 || partNumber > Integer.parseInt((String) session.get("totalParts"))) {
            throw new BizException(400, "非法分片号");
        }
        if (data == null || data.length == 0) {
            throw new BizException(400, "分片内容为空");
        }
        if (data.length > PART_SIZE) {
            throw new BizException(400, "分片过大");
        }
        fileStorage.uploadPart(
                (String) session.get("fileKey"), (String) session.get("storageUploadId"), partNumber, data);
        redis.opsForSet().add(PARTS_KEY + uploadId, String.valueOf(partNumber));
        redis.expire(PARTS_KEY + uploadId, Duration.ofSeconds(SESSION_TTL_SECONDS));
    }

    public List<Integer> receivedParts(Long ownerId, String uploadId) {
        requireSession(ownerId, uploadId);
        Set<String> parts = redis.opsForSet().members(PARTS_KEY + uploadId);
        if (parts == null) {
            return List.of();
        }
        return parts.stream().map(Integer::parseInt).sorted().toList();
    }

    public Map<String, String> complete(Long ownerId, String uploadId) {
        Map<Object, Object> session = requireSession(ownerId, uploadId);
        int totalParts = Integer.parseInt((String) session.get("totalParts"));
        String fileKey = (String) session.get("fileKey");
        String storageUploadId = (String) session.get("storageUploadId");
        List<Integer> received = receivedParts(ownerId, uploadId);
        if (received.size() < totalParts) {
            throw new BizException(400, "分片未全部上传");
        }
        // 与存储侧已落分片逐号核对：Redis 与存储不一致时不合并，避免产出空/缺片文件
        List<Integer> stored = fileStorage.listParts(fileKey, storageUploadId);
        if (!stored.equals(received)) {
            throw new BizException(409, "分片状态不一致，请重试");
        }
        String completedKey = fileStorage.completeMultipart(fileKey, storageUploadId);
        clearSession(uploadId);
        return Map.of("url", FileStorage.SERVED_URL_PREFIX + completedKey, "key", completedKey);
    }

    public void abort(Long ownerId, String uploadId) {
        Map<Object, Object> session = redis.opsForHash().entries(SESSION_KEY + uploadId);
        if (!session.isEmpty()) {
            requireOwner(session, ownerId);
            abortStorage(uploadId, session);
        } else {
            // 会话已过期但 cleanup 元数据仍在：仍按元数据清理存储残留
            Map<Object, Object> cleanup = redis.opsForHash().entries(CLEANUP_KEY + uploadId);
            if (!cleanup.isEmpty()) {
                abortStorage(uploadId, cleanup);
            }
        }
        clearSession(uploadId);
    }

    /** 定时清理过期会话对应的存储残留（S3 multipart / 本地临时目录） */
    @Scheduled(fixedDelay = 600_000)
    public void cleanupExpired() {
        long now = System.currentTimeMillis() / 1000;
        Set<String> expired = redis.opsForZSet().rangeByScore(ACTIVE_ZSET, 0, now);
        if (expired == null || expired.isEmpty()) {
            return;
        }
        for (String uploadId : expired) {
            Map<Object, Object> cleanup = redis.opsForHash().entries(CLEANUP_KEY + uploadId);
            if (!cleanup.isEmpty()) {
                abortStorage(uploadId, cleanup);
            }
            redis.delete(CLEANUP_KEY + uploadId);
            redis.delete(SESSION_KEY + uploadId);
            redis.delete(PARTS_KEY + uploadId);
            redis.opsForZSet().remove(ACTIVE_ZSET, uploadId);
        }
    }

    private Map<Object, Object> requireSession(Long ownerId, String uploadId) {
        Map<Object, Object> session = redis.opsForHash().entries(SESSION_KEY + uploadId);
        if (session.isEmpty()) {
            throw new BizException(400, "上传会话不存在或已过期");
        }
        requireOwner(session, ownerId);
        return session;
    }

    private void requireOwner(Map<Object, Object> session, Long ownerId) {
        String owner = (String) session.get("ownerId");
        if (!String.valueOf(ownerId).equals(owner)) {
            throw new BizException(403, "无权操作该上传会话");
        }
    }

    private void abortStorage(String uploadId, Map<Object, Object> meta) {
        String fileKey = (String) meta.get("fileKey");
        String storageUploadId = (String) meta.get("storageUploadId");
        if (fileKey != null && storageUploadId != null) {
            fileStorage.abortMultipart(fileKey, storageUploadId);
        }
    }

    private void clearSession(String uploadId) {
        redis.delete(SESSION_KEY + uploadId);
        redis.delete(PARTS_KEY + uploadId);
        redis.delete(CLEANUP_KEY + uploadId);
        redis.opsForZSet().remove(ACTIVE_ZSET, uploadId);
    }
}
