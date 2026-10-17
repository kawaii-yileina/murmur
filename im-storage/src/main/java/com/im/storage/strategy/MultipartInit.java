package com.im.storage.strategy;

/**
 * 分片上传初始化结果：uploadId 为存储侧会话标识，fileKey 为合并后的最终文件 key。
 */
public record MultipartInit(String uploadId, String fileKey) {
}
