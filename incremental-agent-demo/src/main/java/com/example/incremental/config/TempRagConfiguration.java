package com.example.incremental.config;

import com.example.incremental.rag.TempRagEmbeddingModel;
import tools.jackson.databind.JsonNode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Configuration
public class TempRagConfiguration {

    @Bean
    TempRagEmbeddingModel tempRagEmbeddingModel(DemoProperties properties) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        WebClient client = WebClient.builder()
                .baseUrl(properties.getRagEmbeddingBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        return text -> {
            if (!StringUtils.hasText(apiKey)) {
                return Mono.error(new IllegalStateException("请配置 DASHSCOPE_API_KEY 以使用临时 RAG"));
            }
            return client.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "model", properties.getRagEmbeddingModel(),
                            "input", text,
                            "dimensions", properties.getRagEmbeddingDimensions(),
                            "encoding_format", "float"))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .map(response -> {
                        JsonNode values = response.path("data").path(0).path("embedding");
                        if (!values.isArray()) {
                            throw new IllegalStateException("Embedding 服务未返回向量");
                        }
                        float[] embedding = new float[values.size()];
                        for (int index = 0; index < values.size(); index++) {
                            embedding[index] = values.get(index).floatValue();
                        }
                        return embedding;
                    });
        };
    }
}

