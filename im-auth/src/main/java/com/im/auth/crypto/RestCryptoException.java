package com.im.auth.crypto;

/** REST 响应加密协商失败（缺头/格式错误/上下文不符），由拦截器转为 400。 */
public class RestCryptoException extends RuntimeException {

    public RestCryptoException(String message) {
        super(message);
    }
}
