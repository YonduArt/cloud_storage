package com.diplom.cloudstorage.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 60) String username,
        @Email @NotBlank String email,
        @NotBlank @Size(min = 6, max = 120) String password,
        @NotBlank @Size(min = 2, max = 120) String displayName
) {
}
