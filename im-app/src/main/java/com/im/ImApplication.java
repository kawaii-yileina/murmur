package com.im;

import com.elaina.chat.bootstrap.annotation.EnableChatServer;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableChatServer
@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan({"com.im.core.*.mapper", "com.im.hermes.model"})
@EnableConfigurationProperties
public class ImApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImApplication.class, args);
    }
}
