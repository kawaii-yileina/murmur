package com.im.storage.impl.s3;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "storage.s3")
public class S3Properties {
    private String endpoint = "http://localhost:8333";
    private String accessKey = "";
    private String secretKey = "";
    private String bucket = "im-files";
}
