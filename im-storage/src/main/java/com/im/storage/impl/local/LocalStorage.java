package com.im.storage.impl.local;

import com.im.common.exception.BizException;
import com.im.storage.strategy.FileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
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
        } catch (IOException e) {
            throw new RuntimeException("Failed to download file", e);
        }
    }

    @Override
    public String getPresignedUrl(String fileKey, int expirySeconds) {
        return "/api/files/local/" + fileKey;
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
