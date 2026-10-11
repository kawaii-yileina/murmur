package com.im.auth.security;

import com.elaina.chat.bootstrap.ChatServerLifecycle;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 只对初始请求（REQUEST dispatch）鉴权：
                // 流式接口（SSE/Flux）结束时容器会做 ASYNC/ERROR dispatch，
                // 若对这些分发再次鉴权，会因响应已提交而抛
                // "Unable to handle the Spring Security Exception" 错误。
                .dispatcherTypeMatchers(
                    DispatcherType.ASYNC, DispatcherType.ERROR,
                    DispatcherType.FORWARD, DispatcherType.INCLUDE).permitAll()
                .requestMatchers(
                    "/api/auth/login",
                    "/api/auth/login/code",
                    "/api/auth/register",
                    "/api/auth/refresh",
                    "/api/auth/send-code",
                    "/api/auth/reset-password",
                    "/api/auth/logout"
                ).permitAll()
                .requestMatchers("/api/files/local/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // 容器探针：健康检查匿名放行（不暴露细节）；指标仍仅管理员
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/metrics", "/actuator/metrics/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            // 未认证返回 401（默认是 403），前端静默续期依赖 401 触发
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                (request, response, authException) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }


    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("*"));
        config.setAllowedMethods(List.of("*"));
        config.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
