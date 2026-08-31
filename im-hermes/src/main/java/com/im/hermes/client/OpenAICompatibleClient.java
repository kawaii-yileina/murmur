package com.im.hermes.client;

import com.im.hermes.model.HermesRequest;
import com.im.hermes.model.HermesResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "hermes.api-type", havingValue = "openai", matchIfMissing = true)
public class OpenAICompatibleClient implements HermesClient {

    private final HermesProperties defaultProperties;

    public OpenAICompatibleClient(HermesProperties properties) {
        this.defaultProperties = properties;
    }

    @Override
    public HermesResponse chat(HermesRequest request) {
        WebClient webClient = buildWebClient(request);
        Map<String, Object> body = buildRequestBody(request, false);

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(HermesResponse.class)
                .block();
    }

    @Override
    public Flux<String> chatStream(HermesRequest request) {
        WebClient webClient = buildWebClient(request);
        Map<String, Object> body = buildRequestBody(request, true);

        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> !line.isBlank() && !line.equals("[DONE]"))
                .map(this::extractContent);
    }

    private WebClient buildWebClient(HermesRequest request) {
        String baseUrl = request.getBaseUrl() != null ? request.getBaseUrl() : defaultProperties.getBaseUrl();
        String apiKey = request.getApiKey() != null ? request.getApiKey() : defaultProperties.getApiKey();
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    private Map<String, Object> buildRequestBody(HermesRequest request, boolean stream) {
        List<Map<String, String>> messages = request.getMessages().stream()
                .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                .toList();

        var body = new java.util.HashMap<String, Object>();
        body.put("model", request.getModel());
        body.put("messages", messages);
        body.put("stream", stream);
        if (request.getMaxTokens() != null) body.put("max_tokens", request.getMaxTokens());
        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        return body;
    }

    private String extractContent(String sseLine) {
        try {
            String json = sseLine.startsWith("data: ") ? sseLine.substring(6) : sseLine;
            if ("[DONE]".equals(json.trim())) return "";
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            var choices = node.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                var delta = choices.get(0).get("delta");
                if (delta != null && delta.has("content")) {
                    return delta.get("content").asText();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse SSE line: {}", sseLine);
        }
        return "";
    }
}