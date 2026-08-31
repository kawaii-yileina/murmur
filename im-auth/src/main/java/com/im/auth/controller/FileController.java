package com.im.auth.controller;

import com.im.common.model.Result;
import com.im.storage.strategy.FileStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorage fileStorage;

    @PostMapping("/upload")
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String fileKey = fileStorage.upload(
            file.getBytes(),
            file.getOriginalFilename() != null ? file.getOriginalFilename() : "file",
            file.getContentType() != null ? file.getContentType() : "application/octet-stream"
        );
        String url = fileStorage.getPresignedUrl(fileKey, 3600);
        return Result.ok(Map.of("url", url, "key", fileKey));
    }

    @GetMapping("/local/{fileKey}")
    public byte[] serveLocal(@PathVariable String fileKey) {
        return fileStorage.download(fileKey);
    }
}
