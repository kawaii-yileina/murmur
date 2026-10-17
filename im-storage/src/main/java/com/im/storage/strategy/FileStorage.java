package com.im.storage.strategy;

import java.io.OutputStream;
import java.util.List;

public interface FileStorage {

    /** 对外统一的文件访问 URL 前缀（由 FileController 提供鉴权下载），与具体存储实现无关 */
    String SERVED_URL_PREFIX = "/api/files/local/";

    String upload(byte[] data, String fileName, String contentType);

    void download(String fileKey, OutputStream out);

    void delete(String fileKey);

    /** 初始化分片上传，返回存储侧会话 id 与最终 fileKey */
    MultipartInit initMultipart(String fileName, String contentType);

    /** 上传一个分片（partNumber 从 1 开始） */
    void uploadPart(String fileKey, String uploadId, int partNumber, byte[] data);

    /** 完成分片上传（按已上传分片合并），返回最终 fileKey */
    String completeMultipart(String fileKey, String uploadId);

    /** 中止分片上传并清理临时数据 */
    void abortMultipart(String fileKey, String uploadId);

    /** 已上传的分片号（升序），用于断点续传 */
    List<Integer> listParts(String fileKey, String uploadId);
}
