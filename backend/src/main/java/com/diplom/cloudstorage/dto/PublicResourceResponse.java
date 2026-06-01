package com.diplom.cloudstorage.dto;

import java.time.Instant;
import java.util.List;

public record PublicResourceResponse(
        String token,
        String targetType,
        String name,
        Long folderId,
        Long parentFolderId,
        String contentType,
        Long sizeBytes,
        Instant createdAt,
        boolean hasPassword,
        List<FolderResponse> folders,
        List<FileResponse> files
) {
}
