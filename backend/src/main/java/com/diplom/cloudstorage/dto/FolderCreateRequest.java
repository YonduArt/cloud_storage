package com.diplom.cloudstorage.dto;

import jakarta.validation.constraints.NotBlank;

public record FolderCreateRequest(
        @NotBlank String name,
        Long parentId
) {
}
