package com.diplom.cloudstorage.dto;

public record FolderResponse(
        Long id,
        String name,
        Long parentId,
        boolean favorite
) {
}
