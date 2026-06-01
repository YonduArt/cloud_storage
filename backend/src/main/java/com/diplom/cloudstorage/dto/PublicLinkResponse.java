package com.diplom.cloudstorage.dto;

import java.time.Instant;

public record PublicLinkResponse(
        Long id,
        String token,
        String targetType,
        Long fileId,
        String fileName,
        Long folderId,
        String folderName,
        String contentType,
        String extension,
        String fileGroup,
        Boolean hasThumbnail,
        Long sizeBytes,
        boolean active,
        Instant createdAt,
        Instant expiresAt,
        boolean expired,
        boolean hasPassword,
        String publicUrl
) {
}
