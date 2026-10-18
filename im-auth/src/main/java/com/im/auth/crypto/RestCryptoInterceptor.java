package com.im.auth.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.common.model.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 只对标注 @EncryptedResponse 的接口做请求头协商；缺头/格式错误直接 400，避免控制器先执行。
 */
@Component
@RequiredArgsConstructor
public class RestCryptoInterceptor implements HandlerInterceptor {

    public static final String ATTR = RestCryptoContext.class.getName();

    private final RestCryptoService cryptoService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        if (!isEncrypted(handlerMethod) || !cryptoService.isEnabled()) {
            return true;
        }
        try {
            request.setAttribute(ATTR, cryptoService.begin(request));
            return true;
        } catch (RestCryptoException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getWriter(), Result.error(400, e.getMessage()));
            return false;
        }
    }

    private static boolean isEncrypted(HandlerMethod handlerMethod) {
        return handlerMethod.getMethodAnnotation(EncryptedResponse.class) != null
                || handlerMethod.getBeanType().isAnnotationPresent(EncryptedResponse.class);
    }
}
