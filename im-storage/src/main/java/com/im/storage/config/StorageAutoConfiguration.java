package com.im.storage.config;

import com.im.storage.strategy.FileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Slf4j
@Configuration
public class StorageAutoConfiguration {

    @Autowired
    public StorageAutoConfiguration(List<FileStorage> storages) {
        log.info("Available storage implementations: {}", 
            storages.stream().map(s -> s.getClass().getSimpleName()).toList());
    }
}
