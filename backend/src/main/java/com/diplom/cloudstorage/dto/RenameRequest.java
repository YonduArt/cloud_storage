package com.diplom.cloudstorage.dto;

import jakarta.validation.constraints.NotBlank;

public record RenameRequest(@NotBlank String name) {
}
