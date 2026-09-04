package com.example.incremental.rag;

import reactor.core.publisher.Mono;

public interface TempRagEmbeddingModel {

    Mono<float[]> embed(String text);
}

