package com.im.storage.exception;

/**
 * 文件不存在（存储层未找到对应对象/路径）。
 * 由 FileController 转换为 HTTP 404，区别于其它内部错误（500）。
 */
public class FileMissingException extends RuntimeException {

    public FileMissingException(String fileKey) {
        super("File not found: " + fileKey);
    }

    public FileMissingException(String fileKey, Throwable cause) {
        super("File not found: " + fileKey, cause);
    }
}
