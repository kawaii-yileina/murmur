package com.im.auth.controller;

import com.im.common.model.Result;
import com.im.storage.strategy.FileStorage;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Slf4j
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

    @GetMapping("/local/{*fileKey}")
    public void serveLocal(@PathVariable String fileKey, HttpServletResponse response) throws IOException {
        // {*fileKey} 捕获的路径带前导 '/'，去掉后再交给存储层（文件 key 按年/月分目录，含多级路径）
        String key = fileKey.startsWith("/") ? fileKey.substring(1) : fileKey;
        // 按文件扩展名推断 MIME，无法识别时回退为 application/octet-stream
        MediaType mediaType = MediaTypeFactory.getMediaType(key).orElse(MediaType.APPLICATION_OCTET_STREAM);
        response.setContentType(mediaType.toString());
        try {
            fileStorage.download(key, response.getOutputStream());
        } catch (RuntimeException e) {
            // 客户端在下载完成前断开连接（切换页面、<img> 重渲染等）属正常现象，不应记为错误
            if (isClientAbort(e)) {
                log.debug("File download aborted by client: {}", key);
                return;
            }
            throw e;
        }
    }

    /**
     * 判断异常链是否由客户端主动断开连接引起。
     */
    private boolean isClientAbort(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof ClientAbortException
                    || current instanceof AsyncRequestNotUsableException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("Connection reset") || message.contains("Broken pipe"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
