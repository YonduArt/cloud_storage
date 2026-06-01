package com.diplom.cloudstorage.dto;

public record PreviewResponse(
        Long fileId,
        String name,
        String contentType,
        String previewKind,
        Long sizeBytes,
        String textSnippet,
        String contentUrl,
        Boolean downloadable
) {
}
