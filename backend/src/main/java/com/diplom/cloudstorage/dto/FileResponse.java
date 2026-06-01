package com.diplom.cloudstorage.dto;

import java.time.Instant;

public record FileResponse(
        Long id,
        String name,
        String contentType,
        String extension,
        String fileGroup,
        Boolean hasThumbnail,
        Long sizeBytes,
        Instant uploadedAt,
        Long folderId,
        Instant deletedAt,
        Instant purgeAfter,
        Instant lastAccessedAt,
        boolean favorite,
        FileIndexSummaryResponse index
) {
}
