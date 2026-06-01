package com.diplom.cloudstorage.dto;

import java.time.Instant;

public record FileEventResponse(
        Long id,
        String action,
        String targetType,
        Long targetId,
        String targetName,
        String details,
        Instant createdAt
) {
}
