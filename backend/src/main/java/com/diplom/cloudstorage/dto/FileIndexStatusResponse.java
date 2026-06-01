package com.diplom.cloudstorage.dto;

import java.time.Instant;
import java.util.List;

public record FileIndexStatusResponse(
        Long fileId,
        String status,
        String contentType,
        Instant indexedAt,
        String errorMessage,
        List<EmbeddingInfo> embeddings
) {
    public record EmbeddingInfo(
            String type,
            String modelName,
            int dimensions,
            Instant createdAt
    ) {
    }
}
