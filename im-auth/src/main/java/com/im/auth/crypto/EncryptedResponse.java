package com.im.auth.crypto;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记返回消息内容（正文/摘要/转写文本）的 REST 接口，响应体 data 使用 im-rest-v1 加密。
 * 纯计数、元数据与文件二进制接口不标注。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface EncryptedResponse {
}
