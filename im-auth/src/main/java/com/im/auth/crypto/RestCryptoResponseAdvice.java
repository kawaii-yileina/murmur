package com.im.auth.crypto;

import com.im.common.model.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 将标注接口的成功响应 data 替换为 im-rest-v1 密文；错误响应保持明文（不含消息内容）。
 */
@RestControllerAdvice
@RequiredArgsConstructor
public class RestCryptoResponseAdvice implements ResponseBodyAdvice<Object> {

    private final RestCryptoService cryptoService;

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        if (returnType.getMethodAnnotation(EncryptedResponse.class) != null) {
            return true;
        }
        Class<?> containingClass = returnType.getContainingClass();
        return containingClass != null && containingClass.isAnnotationPresent(EncryptedResponse.class);
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (!(body instanceof Result<?> result)) {
            return body;
        }
        if (!cryptoService.isEnabled() || result.getCode() != 200 || result.getData() == null) {
            return body;
        }
        Object attribute = ((ServletServerHttpRequest) request).getServletRequest()
                .getAttribute(RestCryptoInterceptor.ATTR);
        if (!(attribute instanceof RestCryptoContext context)) {
            throw new IllegalStateException("REST 加密上下文缺失");
        }
        return new EncryptedResult(result.getCode(), result.getMessage(),
                cryptoService.encrypt(result.getData(), context));
    }
}
