package com.im.auth.controller;

import com.im.common.enums.ChatType;
import com.im.common.exception.BizException;
import com.im.common.model.Result;
import com.im.core.file.entity.FileOwnership;
import com.im.core.file.service.FileOwnershipService;
import com.im.core.file.service.MultipartUploadService;
import com.im.core.group.service.GroupService;
import com.im.storage.exception.FileMissingException;
import com.im.storage.strategy.FileStorage;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorage fileStorage;
    private final FileOwnershipService fileOwnershipService;
    private final MultipartUploadService multipartUploadService;
    private final GroupService groupService;

    /**
     * 上传黑名单（渗透报告 R4-01/R2-05）：可执行/可渲染类型一律拒绝，
     * 阻断"上传 HTML → 同源执行 → 窃取 token"的存储型 XSS 接管链。
     */
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "html", "htm", "xhtml", "svg", "svgz", "xml", "js", "mjs", "cjs",
            "jsp", "jspx", "jspf", "php", "phtml", "asp", "aspx", "asa", "cer", "shtml",
            "exe", "dll", "bat", "cmd", "sh", "ps1", "vbs", "vbe", "hta", "jar", "msi", "scr",
            "htaccess", "htpasswd");

    private static final Set<String> BLOCKED_CONTENT_TYPES = Set.of(
            "text/html", "application/xhtml+xml", "image/svg+xml",
            "application/javascript", "text/javascript", "application/x-javascript");

    /** 单文件上传上限（与 multipart / nginx client_max_body_size 保持一致） */
    private static final long MAX_UPLOAD_BYTES = 100L * 1024 * 1024;

    @PostMapping("/upload")
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BizException(400, "文件大小不能超过 100MB");
        }
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        validateUpload(filename, contentType);
        String fileKey = fileStorage.upload(file.getBytes(), filename, contentType);
        // 统一返回稳定的后端访问 URL（不落存储实现细节）；<img> 等按此 URL 请求，鉴权由 SecurityConfig 保证
        String url = FileStorage.SERVED_URL_PREFIX + fileKey;
        return Result.ok(Map.of("url", url, "key", fileKey));
    }

    @PostMapping("/multipart/init")
    public Result<Map<String, Object>> multipartInit(@RequestAttribute("userId") String userId,
                                                     @RequestBody MultipartInitRequest request) {
        validateUpload(request.getFileName() != null ? request.getFileName() : "file",
                request.getContentType() != null ? request.getContentType() : "application/octet-stream");
        return Result.ok(multipartUploadService.init(Long.parseLong(userId), request.getFileName(),
                request.getContentType(), request.getSize(), request.getTotalParts()));
    }

    @PostMapping("/multipart/part")
    public Result<Void> multipartPart(@RequestAttribute("userId") String userId,
                                      @RequestParam("uploadId") String uploadId,
                                      @RequestParam("partNumber") int partNumber,
                                      @RequestParam("file") MultipartFile file) throws IOException {
        if (file.getSize() > MultipartUploadService.PART_SIZE) {
            throw new BizException(400, "分片过大");
        }
        multipartUploadService.savePart(Long.parseLong(userId), uploadId, partNumber, file.getBytes());
        return Result.ok();
    }

    @GetMapping("/multipart/status")
    public Result<List<Integer>> multipartStatus(@RequestAttribute("userId") String userId,
                                                 @RequestParam("uploadId") String uploadId) {
        return Result.ok(multipartUploadService.receivedParts(Long.parseLong(userId), uploadId));
    }

    @PostMapping("/multipart/complete")
    public Result<Map<String, String>> multipartComplete(@RequestAttribute("userId") String userId,
                                                         @RequestBody UploadIdRequest request) {
        return Result.ok(multipartUploadService.complete(Long.parseLong(userId), request.getUploadId()));
    }

    @PostMapping("/multipart/abort")
    public Result<Void> multipartAbort(@RequestAttribute("userId") String userId,
                                       @RequestBody UploadIdRequest request) {
        multipartUploadService.abort(Long.parseLong(userId), request.getUploadId());
        return Result.ok();
    }

    /** 扩展名与声明 MIME 双重校验，命中黑名单直接拒绝 */
    private void validateUpload(String filename, String contentType) {
        String ext = "";
        String basename = filename;
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        if (slash >= 0) {
            basename = filename.substring(slash + 1);
        }
        int dot = basename.lastIndexOf('.');
        if (dot >= 0) {
            ext = basename.substring(dot + 1).toLowerCase();
        }
        if (BLOCKED_EXTENSIONS.contains(ext)) {
            throw new BizException(400, "不允许上传该类型的文件");
        }
        if (BLOCKED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new BizException(400, "不允许上传该类型的文件");
        }
    }

    @GetMapping("/local/{*fileKey}")
    public void serveLocal(@PathVariable String fileKey,
                           @RequestAttribute("userId") String userId,
                           HttpServletResponse response) throws IOException {
        // {*fileKey} 捕获的路径带前导 '/'，去掉后再交给存储层（文件 key 按年/月分目录，含多级路径）
        String key = fileKey.startsWith("/") ? fileKey.substring(1) : fileKey;
        if (!hasDownloadAccess(key, Long.parseLong(userId))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        // 按文件扩展名推断 MIME，无法识别时回退为 application/octet-stream
        MediaType mediaType = MediaTypeFactory.getMediaType(key).orElse(MediaType.APPLICATION_OCTET_STREAM);
        response.setContentType(mediaType.toString());
        // 下载加固（渗透报告 R4-01/R2-05）：禁止浏览器嗅探改类型；一律按附件下发，
        // 即便有残留的可渲染文件也不会在站点源下被浏览器解析执行
        response.setHeader("X-Content-Type-Options", "nosniff");
        String basename = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
        response.setHeader("Content-Disposition", "attachment; filename=\"" + basename + "\"");
        try {
            fileStorage.download(key, response.getOutputStream());
        } catch (FileMissingException e) {
            // 文件不存在属正常情况（历史 URL、已删除），返回 404 而非 500
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
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
     * 会话级下载鉴权（T8）：已登记归属的附件仅归属会话的参与人可读——
     * 私聊校验双方、群聊校验群成员；一个文件可被多个会话引用（转发），
     * 命中任意一条归属即放行；未登记归属的文件（头像、表情等）按公开放行。
     */
    private boolean hasDownloadAccess(String fileKey, Long callerId) {
        List<FileOwnership> ownerships = fileOwnershipService.findAllByKey(fileKey);
        if (ownerships.isEmpty()) {
            return true;
        }
        for (FileOwnership ownership : ownerships) {
            if (callerId.equals(ownership.getOwnerUserId())) {
                return true;
            }
            boolean matched = ChatType.GROUP.getCode() == ownership.getChatType()
                    ? groupService.isMember(ownership.getTargetId(), callerId)
                    : callerId.equals(ownership.getTargetId());
            if (matched) {
                return true;
            }
        }
        return false;
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

    /** 分片上传初始化请求 */
    @Data
    public static class MultipartInitRequest {
        private String fileName;
        private String contentType;
        private long size;
        private int totalParts;
    }

    /** 携带 uploadId 的请求（完成/中止） */
    @Data
    public static class UploadIdRequest {
        private String uploadId;
    }
}
