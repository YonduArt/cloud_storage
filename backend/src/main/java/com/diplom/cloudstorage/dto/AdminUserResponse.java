package com.diplom.cloudstorage.dto;

import java.time.Instant;

public record AdminUserResponse(
        Long id,
        String username,
        String email,
        String displayName,
        Long usedSpace,
        Long storageQuotaBytes,
        Long fileCount,
        boolean enabled,
        String role,
        Instant createdAt
) {
}
