package com.diplom.cloudstorage.integration.dto;

import java.time.Instant;
import java.util.List;

public record IntegrationClientCreateResponse(
        Long id,
        String name,
        String apiKey,
        List<String> scopes,
        boolean enabled,
        Instant createdAt
) {
}
