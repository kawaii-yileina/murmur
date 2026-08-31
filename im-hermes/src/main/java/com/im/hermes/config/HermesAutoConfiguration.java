package com.im.hermes.config;

import com.im.hermes.client.HermesProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "hermes.enabled", havingValue = "true")
public class HermesAutoConfiguration {

    @Autowired
    public HermesAutoConfiguration(HermesProperties properties) {
        log.info("Hermes Agent integration enabled, apiType: {}, baseUrl: {}", 
            properties.getApiType(), properties.getBaseUrl());
    }
}