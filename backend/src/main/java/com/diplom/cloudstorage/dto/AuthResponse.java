package com.diplom.cloudstorage.dto;

public record AuthResponse(
        String message,
        UserResponse user
) {
}
