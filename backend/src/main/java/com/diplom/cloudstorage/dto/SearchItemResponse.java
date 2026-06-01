package com.diplom.cloudstorage.dto;

import java.time.Instant;

public record SearchItemResponse(
        String type,
        Long id,
        String name,
        Long parentId,
        Long sizeBytes,
        Instant createdAt,
        String matchType,
        Double score,
        String contentType,
        String extension,
        String fileGroup,
        Boolean hasThumbnail
) {
}
