package com.im.hermes.client;

import com.im.hermes.model.HermesRequest;
import com.im.hermes.model.HermesResponse;
import reactor.core.publisher.Flux;

public interface HermesClient {
    HermesResponse chat(HermesRequest request);
    Flux<String> chatStream(HermesRequest request);
}