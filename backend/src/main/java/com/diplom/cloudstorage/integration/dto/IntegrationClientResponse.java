package com.diplom.cloudstorage.integration.dto;

import java.time.Instant;
import java.util.List;

public record IntegrationClientResponse(
        Long id,
        String name,
        List<String> scopes,
        boolean enabled,
        Instant createdAt,
        Instant lastUsedAt
) {
}
