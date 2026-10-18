package com.im.auth.crypto;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册 REST 加密协商拦截器（仅 /api/**，且只对标注接口生效）。 */
@Configuration
@RequiredArgsConstructor
public class RestCryptoWebConfig implements WebMvcConfigurer {

    private final RestCryptoInterceptor restCryptoInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(restCryptoInterceptor).addPathPatterns("/api/**");
    }
}
