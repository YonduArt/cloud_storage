package com.diplom.cloudstorage.dto;

public record AdminUserUpdateRequest(
        Boolean enabled,
        Long storageQuotaBytes
) {
}
