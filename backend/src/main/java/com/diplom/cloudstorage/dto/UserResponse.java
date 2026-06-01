package com.diplom.cloudstorage.dto;

public record UserResponse(
        Long id,
        String username,
        String email,
        String displayName,
        Long usedSpace,
        Long storageQuotaBytes,
        boolean enabled,
        String role
) {
}
