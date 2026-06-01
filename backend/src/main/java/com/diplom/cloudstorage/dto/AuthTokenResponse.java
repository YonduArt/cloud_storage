package com.diplom.cloudstorage.dto;

public record AuthTokenResponse(
        String token,
        String tokenType,
        UserResponse user
) {
}
