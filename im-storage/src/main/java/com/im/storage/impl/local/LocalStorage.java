package com.im.storage.impl.local;

import com.im.storage.strategy.FileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
            String objectName = "" + LocalDate.now().getYear()+LocalDate.now().getMonth() + LocalDate.now().getDayOfMonth() + "/" +UUID.randomUUID() + ext;
            Path filePath = Paths.get(basePath, objectName);
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, data);
            log.info("File uploaded locally: {}", objectName);
            return objectName;
        } catch (IOException e) {
            throw new RuntimeException("Failed to upload file locally", e);
        }
    }

    @Override
    public byte[] download(String fileKey) {
        try {
            Path filePath = Paths.get(basePath, fileKey);
            return Files.readAllBytes(filePath);
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
            Path filePath = Paths.get(basePath, fileKey);
            Files.deleteIfExists(filePath);
            log.info("File deleted locally: {}", fileKey);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete file", e);
        }
    }
}
