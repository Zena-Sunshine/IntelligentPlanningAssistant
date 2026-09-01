package com.voyageiq.business.service;

import com.voyageiq.business.config.VoyageIqProperties;
import io.netty.channel.ChannelOption;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

@Service
public class AgentGatewayService {
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE = new ParameterizedTypeReference<>() {};
    private final WebClient client;
    private final VoyageIqProperties properties;

    public AgentGatewayService(WebClient.Builder builder, VoyageIqProperties properties) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.agent().connectTimeout().toMillis())
                .responseTimeout(properties.agent().responseTimeout());
        this.client = builder.baseUrl(properties.agent().baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient)).build();
    }

    public Flux<ServerSentEvent<String>> stream(Map<String, Object> request) {
        return client.post()
                .uri("/internal/v1/agent/chat/stream")
                .header("X-Internal-Service-Key", properties.security().internalServiceKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(SSE_TYPE);
    }
}

