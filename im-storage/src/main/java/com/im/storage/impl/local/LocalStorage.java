package com.im.storage.impl.local;

import com.im.common.exception.BizException;
import com.im.storage.exception.FileMissingException;
import com.im.storage.strategy.FileStorage;
import com.im.storage.strategy.MultipartInit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@ConditionalOnProperty(name = "storage.type", havingValue = "local")
public class LocalStorage implements FileStorage {

    @Value("${storage.local.base-path:./uploads}")
    private String basePath;

    @Override
    public String upload(byte[] data, String fileName, String contentType) {
        try {
            String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf(".")) : "";
            LocalDate now = LocalDate.now();
            // 按年/月分目录，避免单目录文件过多
            String objectName = String.format("%04d/%02d/%s%s",
                    now.getYear(), now.getMonthValue(), UUID.randomUUID(), ext);
            Path filePath = resolveSafe(objectName);
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, data);
            log.info("File uploaded locally: {}", objectName);
            return objectName;
        } catch (IOException e) {
            throw new RuntimeException("Failed to upload file locally", e);
        }
    }

    @Override
    public void download(String fileKey, OutputStream out) {
        try {
            Files.copy(resolveSafe(fileKey), out);
        } catch (NoSuchFileException e) {
            throw new FileMissingException(fileKey, e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to download file", e);
        }
    }

    @Override
    public void delete(String fileKey) {
        try {
            Files.deleteIfExists(resolveSafe(fileKey));
            log.info("File deleted locally: {}", fileKey);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete file", e);
        }
    }

    @Override
    public MultipartInit initMultipart(String fileName, String contentType) {
        try {
            String ext = fileName != null && fileName.contains(".")
                    ? fileName.substring(fileName.lastIndexOf(".")) : "";
            LocalDate now = LocalDate.now();
            String objectName = String.format("%04d/%02d/%s%s",
                    now.getYear(), now.getMonthValue(), UUID.randomUUID(), ext);
            String sessionId = UUID.randomUUID().toString();
            Files.createDirectories(partDir(sessionId));
            return new MultipartInit(sessionId, objectName);
        } catch (IOException e) {
            throw new RuntimeException("Failed to init multipart upload locally", e);
        }
    }

    @Override
    public void uploadPart(String fileKey, String uploadId, int partNumber, byte[] data) {
        try {
            Path dir = partDir(uploadId);
            Files.createDirectories(dir);
            Files.write(dir.resolve("part-" + partNumber), data);
        } catch (IOException e) {
            throw new RuntimeException("Failed to upload part locally", e);
        }
    }

    @Override
    public String completeMultipart(String fileKey, String uploadId) {
        try {
            Path dir = partDir(uploadId);
            List<Path> parts = listPartFiles(dir);
            if (parts.isEmpty()) {
                throw new RuntimeException("No multipart parts to complete: " + fileKey);
            }
            Path target = resolveSafe(fileKey);
            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(target)) {
                for (Path part : parts) {
                    Files.copy(part, out);
                }
            }
            deleteDir(dir);
            log.info("Multipart upload completed locally: {}", fileKey);
            return fileKey;
        } catch (IOException e) {
            throw new RuntimeException("Failed to complete multipart upload locally", e);
        }
    }

    @Override
    public void abortMultipart(String fileKey, String uploadId) {
        deleteDir(partDir(uploadId));
    }

    @Override
    public List<Integer> listParts(String fileKey, String uploadId) {
        try {
            return listPartFiles(partDir(uploadId)).stream()
                    .map(p -> partNumberOf(p.getFileName().toString()))
                    .filter(n -> n != Integer.MAX_VALUE)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** 分片临时目录（位于 base-path 内） */
    private Path partDir(String sessionId) {
        Path base = Paths.get(basePath).toAbsolutePath().normalize();
        return base.resolve(".multipart").resolve(sessionId).normalize();
    }

    private List<Path> listPartFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith("part-"))
                    .sorted(Comparator.comparingInt(p -> partNumberOf(p.getFileName().toString())))
                    .toList();
        }
    }

    private int partNumberOf(String name) {
        try {
            return Integer.parseInt(name.substring("part-".length()));
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private void deleteDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // best-effort：单个分片删除失败不影响其它清理
                    log.warn("Failed to delete multipart temp file {}: {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list multipart temp dir {}: {}", dir, e.getMessage());
        }
        try {
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            log.warn("Failed to delete multipart temp dir {}: {}", dir, e.getMessage());
        }
    }

    /**
     * 规范化文件路径并校验其必须位于 base-path 之内，防止 ../ 路径穿越。
     */
    private Path resolveSafe(String fileKey) {
        Path base = Paths.get(basePath).toAbsolutePath().normalize();
        Path target = base.resolve(fileKey).normalize();
        if (!target.startsWith(base)) {
            throw new BizException(400, "非法文件路径");
        }
        return target;
    }
}
