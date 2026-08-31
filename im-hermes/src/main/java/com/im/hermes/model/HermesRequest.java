package com.im.hermes.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HermesRequest {
    private String model;
    private List<HermesMessage> messages;
    private Integer maxTokens;
    private Double temperature;
    private String sessionId;
    private String baseUrl;
    private String apiKey;
}